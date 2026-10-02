package eu.neydev.saver.core.extract.backend;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.Extractor;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.media.MediaSniffer;
import eu.neydev.saver.core.media.MimeTypes;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
 *
 * <p>Three guards keep the scraper honest on pages whose media is gone or was never
 * scrapable: the page's own player verdict ({@code ytInitialPlayerResponse} on YouTube)
 * is read BEFORE any candidate, so a deleted or age-gated video reports UNAVAILABLE
 * instead of "success with the poster"; a page that DECLARES video/audio but exposes
 * no downloadable media file never degrades to its poster image - the poster of a
 * 60 MB video is not the video, shipping it as one is a lie; and a candidate whose
 * download turns out to be an HTML page (a player shell named by og:video or by a
 * JSON-LD embedUrl) is rejected after the fact - the first bytes of the answer are
 * the witness, because such pages arrive with HTTP 200 and text/html.
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

        // A watch page of a deleted, private or age-gated video still answers HTTP 200:
        // the verdict lives inside the page's own player response. Reading it first is
        // the difference between "the media is unavailable" and a bogus poster delivery.
        JsonNode player = playerResponse(document);
        checkPlayability(player);

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

        // The poster guard: og:image of a video page is the COVER of the content, not
        // the content. Delivering it as "the video" (a 143 KB image instead of a 63 MB
        // clip) is worse than an honest error, because the user sees a green success.
        if (videoFiles.isEmpty() && declaresVideoOrAudio(document, player)) {
            throw new ExtractionException(Category.UNSUPPORTED, BACKEND_ID,
                    "Page declares video/audio but exposes no downloadable media file; "
                            + "needs a tool backend");
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
                items.add(downloadOne(request, mediaUrl, items.size(), null));
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

        ExtractedItem item = downloadOne(request, finalUrl, 0, contentType);

        return new ExtractionResult(request.source(), BACKEND_ID, List.of(item),
                null, null, finalUrl.toString(), false);

    }

    private ExtractedItem downloadOne(ExtractionRequest request, URI mediaUrl, int index,
                                      String contentType) {

        String guessedName = fileNameFrom(mediaUrl, index,
                contentType == null || contentType.isBlank() ? null : contentType);
        Path destination = uniquePath(request.workDir(), guessedName);

        long bytes = http.download(mediaUrl, destination, request.maxFileBytes(), BACKEND_ID);

        // A mis-pointed og:video / JSON-LD url can still name a PLAYER PAGE instead of
        // a file: the download itself succeeds (HTTP 200, text/html) and the user would
        // receive a saved web page as "the video". The first bytes of the answer are
        // the only witness - they veto the candidate and keep the work dir clean.
        if (MediaSniffer.isHtmlPage(destination)) {

            deleteQuietly(destination);

            throw new ExtractionException(Category.UNSUPPORTED, BACKEND_ID,
                    "Media url served an HTML player page instead of a file: " + mediaUrl);
        }

        // A CDN url often ends in an id instead of a name (…/KLz17Yc9shs): without an
        // extension the file would reach the user as an anonymous document card, so
        // the header gets the last word on what the file is.
        destination = ensureExtension(destination);

        MediaKind kind = MediaKind.fromExtension(destination.getFileName().toString());

        return new ExtractedItem(kind, destination, bytes, null, null, null, null,
                mediaUrl.toString());

    }

    private static void deleteQuietly(Path file) {

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Cannot delete {}: {}", file, e.getMessage());
        }

    }

    /** Renames an extensionless download to what its own header promises, best effort. */
    static Path ensureExtension(Path file) {

        if (MediaSniffer.hasExtension(file.getFileName().toString())) {
            return file;
        }

        return MediaSniffer.extension(file).map(ext -> {

            Path renamed = uniquePath(file.getParent(),
                    file.getFileName().toString() + "." + ext);

            try {
                Files.move(file, renamed);
                return renamed;
            } catch (IOException e) {
                log.debug("Cannot rename {} to {}: {}", file, renamed, e.getMessage());
                return file;
            }

        }).orElse(file);

    }

    // ---- page parsing ---------------------------------------------------------

    /**
     * The YouTube player response embedded in watch/shorts pages, or null on any other
     * site. The object is minified JSON assigned to a global variable, so it is cut out
     * by brace matching instead of a regex over the whole script blob.
     */
    private static @Nullable JsonNode playerResponse(Document document) {

        for (Element script : document.select("script")) {

            String data = script.data();
            int marker = data.indexOf("ytInitialPlayerResponse");

            if (marker < 0) {
                continue;
            }

            int open = data.indexOf('{', marker);
            int close = open < 0 ? -1 : matchBrace(data, open);

            if (close < 0) {
                continue;
            }

            try {
                return MAPPER.readTree(data.substring(open, close + 1));
            } catch (Exception ignored) {
                // A half-written player response is not worth failing the parse over;
                // the meta-tag candidates below still get their chance.
            }

        }

        return null;
    }

    /** Index of the brace closing the object opened at {@code open}, strings respected. */
    private static int matchBrace(String json, int open) {

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = open; i < json.length(); i++) {

            char c = json.charAt(i);

            if (inString) {

                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }

                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {

                depth--;

                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * The page's own verdict on its media: YouTube's playabilityStatus knows the video
     * is deleted, private, age-gated or region-locked long before any scraper guesses
     * it from missing tags. Unknown statuses are NOT a veto - only the explicit "no"
     * answers are, everything else falls through to the normal candidate search.
     */
    private static void checkPlayability(@Nullable JsonNode player) throws ExtractionException {

        if (player == null) {
            return;
        }

        JsonNode playability = player.path("playabilityStatus");
        String status = playability.path("status").asText("");
        String reason = playability.path("reason").asText("");

        Category category = switch (status) {

            // yt-dlp's own wording for the age gate is "Sign in to confirm your age":
            // the login form is the symptom, the age check is the reason.
            case "LOGIN_REQUIRED" -> reason.toLowerCase(Locale.ROOT).contains("age")
                    ? Category.AGE_RESTRICTED
                    : Category.LOGIN_REQUIRED;
            case "UNPLAYABLE", "ERROR" -> unplayableCategory(reason);
            case "LIVE_STREAM_OFFLINE" -> Category.LIVE_STREAM;
            default -> null;
        };

        if (category != null) {
            throw new ExtractionException(category, BACKEND_ID,
                    "The page's own player reports " + status + ": " + reason);
        }
    }

    private static Category unplayableCategory(String reason) {

        String lower = reason.toLowerCase(Locale.ROOT);

        if (lower.contains("private")) {
            return Category.PRIVATE;
        }

        if (lower.contains("country") || lower.contains("region")) {
            return Category.GEO_BLOCKED;
        }

        return Category.UNAVAILABLE;
    }

    /**
     * Whether the page presents itself as video/audio content: og:type, the twitter
     * player card, a {@code <video>} element, a JSON-LD VideoObject or an embedded
     * player response. Such a page without a single downloadable media URL has nothing
     * for the scraper - its images are posters, not content.
     */
    private static boolean declaresVideoOrAudio(Document document, @Nullable JsonNode player) {

        if (player != null) {
            return true;
        }

        for (Element meta : document.select("meta[property=og:type]")) {

            String type = meta.attr("content").toLowerCase(Locale.ROOT);

            if (type.startsWith("video") || type.startsWith("audio")
                    || type.startsWith("music.")) {
                return true;
            }
        }

        for (Element meta : document.select("meta[name=twitter:card]")) {

            if (meta.attr("content").equalsIgnoreCase("player")) {
                return true;
            }
        }

        if (document.selectFirst("video") != null) {
            return true;
        }

        for (Element script : document.select("script[type=application/ld+json]")) {

            if (ldDeclaresType(script.data(), Set.of("VideoObject", "AudioObject"), 0)) {
                return true;
            }
        }

        return false;
    }

    private static boolean ldDeclaresType(String json, Set<String> wanted, int depth) {

        if (depth > 4 || json == null || json.isBlank()) {
            return false;
        }

        try {
            return ldNodeDeclaresType(MAPPER.readTree(json), wanted, depth);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean ldNodeDeclaresType(JsonNode node, Set<String> wanted, int depth) {

        if (node == null || depth > 4) {
            return false;
        }

        if (node.isArray()) {

            for (JsonNode child : node) {

                if (ldNodeDeclaresType(child, wanted, depth + 1)) {
                    return true;
                }
            }

            return false;
        }

        if (!node.isObject()) {
            return false;
        }

        JsonNode type = node.path("@type");

        if (type.isTextual() && wanted.contains(type.asText())) {
            return true;
        }

        if (type.isArray()) {

            for (JsonNode one : type) {

                if (one.isTextual() && wanted.contains(one.asText())) {
                    return true;
                }
            }
        }

        return ldNodeDeclaresType(node.path("@graph"), wanted, depth + 1);
    }

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

    /** Minimal JSON-LD walk: finds contentUrl of the requested object types. */
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

            // contentUrl ONLY. schema.org defines embedUrl as the URL of a PLAYER PAGE,
            // not of a media file - and YouTube's JSON-LD carries exactly that:
            // https://www.youtube.com/embed/<id>. Collecting it made the bot download
            // the player page (a ~143 KB HTML document) and ship it as "the video",
            // with the Original button leading to a page whose player reports an
            // error. A player page is never the content; the poster guard below is
            // the honest answer for pages whose only "video" URL is a player.
            String value = node.path("contentUrl").asText("");

            // Absolute or site-relative: resolveAll() turns both into URIs, and
            // relative contentUrls are the COMMON case in hand-written JSON-LD.
            if (!value.isBlank() && (value.startsWith("http") || value.startsWith("/"))) {
                sink.add(value);
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

        // application/octet-stream is the server saying "no idea": stamping .bin from
        // it would bury the magic-byte sniff that actually knows the container.
        if (!name.contains(".") && contentType != null
                && !contentType.startsWith("application/octet-stream")) {
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
