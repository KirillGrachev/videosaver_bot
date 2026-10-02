package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.Extractor;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.media.MimeTypes;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The built-in OpenGraph/JSON-LD scraper - the "supports anything with a public page"
 * backend, and the reason the catalog is a routing table instead of a whitelist. No
 * external tool needed: one capped HTTP fetch under {@link eu.neydev.saver.core.security.SsrfGuard}
 * control, one jsoup parse, downloads of the found media URLs.
 *
 * <p>Candidate order matters: an explicit og:video/twitter:player:stream is the real
 * content; og:image is the poster of that content and only becomes THE result when no
 * video was found. Streaming manifests (.m3u8/.mpd) are NOT downloaded here - they are
 * yt-dlp's job, and the chain gives yt-dlp its chance; when it already ran and failed,
 * a manifest-only page honestly reports UNSUPPORTED.
 */
public final class GenericHttpExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(GenericHttpExtractor.class);

    public static final String BACKEND_ID = "http";

    /** Pages above this are not worth parsing (and cost real memory on a busy bot). */
    private static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;

    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mp4", "webm", "mov", "m4v", "3gp", "mkv", "avi");

    /** One shared mapper: JSON-LD blocks appear by the dozen on modern pages. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final SafeHttp http;

    public GenericHttpExtractor(SafeHttp http) {
        this.http = http;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) throws ExtractionException {

        SafeHttp.Response response = http.fetch(request.url(), MAX_PAGE_BYTES, BACKEND_ID);

        // The URL was a file all along (content-type says so): download it directly.
        if (!response.isHtml()) {
            return directDownload(request, response.finalUrl(), response.contentType());
        }

        Document document = Jsoup.parse(response.bodyAsString(), response.finalUrl().toString());

        String title = firstNonBlank(
                meta(document, "og:title"),
                meta(document, "twitter:title"),
                document.title());

        List<URI> videos = candidates(document, response.finalUrl(),
                "og:video:secure_url", "og:video:url", "og:video",
                "twitter:player:stream", "al:android:url");
        videos.addAll(jsonLd(document, response.finalUrl(), "VideoObject", "AudioObject"));
        videos.addAll(htmlVideoSources(document, response.finalUrl()));

        // Precedence for images: JSON-LD contentUrl is usually the ORIGINAL, og:image
        // is usually a downscaled preview card - so og goes last, and srcset's largest
        // candidate joins between them.
        List<URI> images = jsonLd(document, response.finalUrl(), "ImageObject");
        images.addAll(candidates(document, response.finalUrl(),
                "twitter:image", "twitter:image:src"));
        images.addAll(srcsetCandidates(document, response.finalUrl()));
        images.addAll(candidates(document, response.finalUrl(),
                "og:image:secure_url", "og:image:url", "og:image", "image_src"));
        images.addAll(linkRelImageSrc(document, response.finalUrl()));

        List<URI> videoFiles = filterManifests(dedupe(videos));

        if (videoFiles.isEmpty() && !videos.isEmpty()) {
            throw new ExtractionException(Category.UNSUPPORTED, BACKEND_ID,
                    "Page exposes only streaming manifests (m3u8/mpd); needs yt-dlp");
        }

        List<URI> chosen = !videoFiles.isEmpty() ? videoFiles : dedupe(images);

        if (chosen.isEmpty()) {
            throw new ExtractionException(Category.NOT_FOUND, BACKEND_ID,
                    "No downloadable media found on " + response.finalUrl());
        }

        List<ExtractedItem> items = new ArrayList<>();
        List<ExtractionException> failures = new ArrayList<>();

        for (URI mediaUrl : chosen) {

            if (items.size() >= request.maxItems()) {
                break;
            }

            try {
                items.add(downloadOne(request, mediaUrl, items.size()));
                request.progress().onProgress(null, null, items.size(), chosen.size());
            } catch (ExtractionException e) {

                // One dead CDN URL must not kill the rest of the gallery.
                log.debug("Skipping {} ({}): {}", mediaUrl, e.category(), e.getMessage());

                if (e.category() == Category.CANCELLED) {
                    throw e;
                }

                failures.add(e);

            }

        }

        if (items.isEmpty()) {
            // Nothing survived: report the MOST SPECIFIC failure ("too large" beats
            // a generic network error), never a bare "everything failed".
            throw ExtractionException.mostSpecific(failures);
        }

        boolean truncated = chosen.size() > items.size();

        return new ExtractionResult(request.source(), BACKEND_ID, items,
                title, null, response.finalUrl().toString(), truncated);

    }

    private ExtractionResult directDownload(ExtractionRequest request, URI finalUrl,
                                            String contentType) throws ExtractionException {

        ExtractedItem item = downloadOne(request, finalUrl, 0);

        return new ExtractionResult(request.source(), BACKEND_ID, List.of(item),
                null, null, finalUrl.toString(), false);

    }

    private ExtractedItem downloadOne(ExtractionRequest request, URI mediaUrl, int index) {

        String guessedName = fileNameFrom(mediaUrl, index, null);
        Path destination = uniquePath(request.workDir(), guessedName);

        long bytes = http.download(mediaUrl, destination, request.maxFileBytes(), BACKEND_ID);

        MediaKind kind = MediaKind.fromExtension(destination.getFileName().toString());

        return new ExtractedItem(kind, destination, bytes, null, null, null, null,
                mediaUrl.toString());

    }

    // ---- page parsing ---------------------------------------------------------

    private static List<URI> candidates(Document document, URI base, String... metaNames) {

        Set<String> urls = new LinkedHashSet<>();

        for (String name : metaNames) {

            for (Element element : document.select(
                    "meta[property=" + name + "], meta[name=" + name + "]")) {

                String content = element.attr("content");

                if (!content.isBlank()) {
                    urls.add(content);
                }

            }

        }

        return resolveAll(urls, base);

    }

    private static List<URI> htmlVideoSources(Document document, URI base) {

        Set<String> urls = new LinkedHashSet<>();

        for (Element video : document.select("video[src], video source[src]")) {

            String src = video.attr("src");

            if (!src.isBlank()) {
                urls.add(src);
            }

        }

        return resolveAll(urls, base);

    }

    /** Minimal JSON-LD walk: finds contentUrl/embedUrl of the requested object types. */
    private static List<URI> jsonLd(Document document, URI base, String... types) {

        Set<String> urls = new LinkedHashSet<>();
        Set<String> wanted = Set.of(types);

        for (Element script : document.select("script[type=application/ld+json]")) {
            collectLdUrls(script.data(), wanted, urls, 0);
        }

        return resolveAll(urls, base);

    }

    private static void collectLdUrls(String json, Set<String> wanted, Set<String> sink, int depth) {

        if (depth > 4 || json == null || json.isBlank()) {
            return;
        }

        try {

            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(json);
            collectLdNode(node, wanted, sink, depth);

        } catch (Exception ignored) {
            // Malformed JSON-LD is everywhere in the wild; the meta tags still work.
        }

    }

    private static void collectLdNode(com.fasterxml.jackson.databind.JsonNode node,
                                      Set<String> wanted, Set<String> sink, int depth) {

        if (node == null || depth > 4) {
            return;
        }

        if (node.isArray()) {
            node.forEach(child -> collectLdNode(child, wanted, sink, depth + 1));
            return;
        }

        if (!node.isObject()) {
            return;
        }

        String type = node.path("@type").asText("");

        boolean matches = wanted.isEmpty() || wanted.contains(type);

        if (matches) {

            for (String field : List.of("contentUrl", "embedUrl")) {

                String value = node.path(field).asText("");

                // Absolute or site-relative: resolveAll() turns both into URIs, and
                // relative contentUrls are the COMMON case in hand-written JSON-LD.
                if (!value.isBlank() && (value.startsWith("http") || value.startsWith("/"))) {
                    sink.add(value);
                }

            }

        }

        node.path("@graph").forEach(child -> collectLdNode(child, wanted, sink, depth + 1));

    }

    /** Parses every img/source srcset and keeps the largest candidate of each. */
    private static List<URI> srcsetCandidates(Document document, URI base) {

        Set<String> urls = new LinkedHashSet<>();

        for (Element element : document.select("img[srcset], source[srcset]")) {

            String best = null;
            double bestWidth = -1;

            for (String candidate : element.attr("srcset").split(",")) {

                String[] parts = candidate.trim().split("\\s+");

                if (parts.length == 0 || parts[0].isBlank()) {
                    continue;
                }

                double width = 0;

                if (parts.length > 1 && parts[1].endsWith("w")) {
                    try {
                        width = Double.parseDouble(parts[1].substring(0, parts[1].length() - 1));
                    } catch (NumberFormatException ignored) {
                        // a malformed descriptor simply sorts as unknown
                    }
                }

                if (width >= bestWidth) {
                    bestWidth = width;
                    best = parts[0];
                }

            }

            if (best != null) {
                urls.add(best);
            }

        }

        return resolveAll(urls, base);

    }

    /** <link rel="image_src"> - the oldest preview hint, still served by many CMS. */
    private static List<URI> linkRelImageSrc(Document document, URI base) {

        Set<String> urls = new LinkedHashSet<>();

        for (Element element : document.select("link[rel=image_src]")) {

            String href = element.attr("href");

            if (!href.isBlank()) {
                urls.add(href);
            }

        }

        return resolveAll(urls, base);

    }

    private static List<URI> resolveAll(Set<String> urls, URI base) {

        List<URI> result = new ArrayList<>(urls.size());

        for (String url : urls) {

            try {

                URI resolved = base.resolve(url.trim());
                String scheme = resolved.getScheme();

                if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    result.add(resolved);
                }

            } catch (IllegalArgumentException ignored) {
                // data:, blob: and broken relative URLs die here, quietly
            }

        }

        return result;

    }

    /**
     * Deduplicates ACROSS the collector lists: the same URL typically appears in
     * og:image AND twitter:image AND JSON-LD, and without this the user receives
     * one file three times (as x.jpg, x-2.jpg, x-3.jpg).
     */
    private static List<URI> dedupe(List<URI> urls) {

        Set<String> seen = new LinkedHashSet<>();
        List<URI> result = new ArrayList<>(urls.size());

        for (URI url : urls) {

            // Normalize only the cheap parts: full string equality after lowercasing
            // the host. Query strings are significant (CDN signatures) and kept as is.
            String key = url.toString().toLowerCase(java.util.Locale.ROOT);

            if (seen.add(key)) {
                result.add(url);
            }

        }

        return result;

    }

    private static List<URI> filterManifests(List<URI> urls) {

        List<URI> result = new ArrayList<>(urls.size());

        for (URI url : urls) {

            String path = url.getPath() == null ? "" : url.getPath().toLowerCase(Locale.ROOT);

            if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
                continue;
            }

            result.add(url);

        }

        return result;

    }

    private static String meta(Document document, String name) {

        Element element = document.selectFirst(
                "meta[property=" + name + "], meta[name=" + name + "]");

        return element == null ? null : element.attr("content");

    }

    // ---- file naming ------------------------------------------------------------

    static String fileNameFrom(URI url, int index, String contentType) {

        String path = url.getPath() == null ? "" : url.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : "";

        // Percent-decoded, control chars and path separators out, length capped:
        // a hostile Content-Disposition must not escape the work dir.
        name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");

        if (name.length() > 120) {
            name = name.substring(0, 120);
        }

        if (name.isBlank() || name.equals("_")) {
            name = "media-" + (index + 1);
        }

        if (!name.contains(".") && contentType != null) {
            name = name + "." + MimeTypes.extensionFor(contentType);
        }

        return name;

    }

    /** Avoids collisions when two URLs end in the same file name. */
    static Path uniquePath(Path dir, String fileName) {

        Path candidate = dir.resolve(fileName);

        for (int i = 2; Files.exists(candidate); i++) {

            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";

            candidate = dir.resolve(base + "-" + i + ext);

        }

        return candidate;

    }

    private static String firstNonBlank(String... values) {

        for (String value : values) {

            if (value != null && !value.isBlank()) {
                return value;
            }

        }

        return null;

    }

    /** Video extension check used by tests and the direct-link backend. */
    public static boolean isVideoExtension(String fileName) {

        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');

        return dot >= 0 && VIDEO_EXTENSIONS.contains(lower.substring(dot + 1));

    }

}
