package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.Extractor;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.util.ProcessRunner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gallery-dl backend: image galleries, albums and art communities (Pixiv, DeviantArt,
 * Fur Affinity, ArtStation, Imgur albums, Tumblr blogs, Flickr...). Complements yt-dlp,
 * which is video-first: a 40-image album is one gallery-dl call and a hopeless yt-dlp
 * one.
 *
 * <p>Same contract as the other backends: download into the job-exclusive work dir,
 * then scan it. gallery-dl creates {@code site/gallery/} subfolders, so the scan is
 * recursive and the item count is capped client-side on top of {@code --range}.
 */
public final class GalleryDlExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(GalleryDlExtractor.class);

    public static final String BACKEND_ID = "gallerydl";

    private static final int NAME_LENGTH_LIMIT = 120;

    public record Options(@Nullable String proxy,
                          @Nullable String cookiesFile,
                          Duration timeout) {

        public static Options defaults() {
            return new Options(null, null, Duration.ofMinutes(10));
        }

    }

    private final Toolchain tools;
    private final Options options;

    public GalleryDlExtractor(Toolchain tools, Options options) {
        this.tools = tools;
        this.options = options;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public boolean available() {
        return tools.hasGalleryDl();
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) throws ExtractionException {

        // Fetch N+1 on purpose: seeing the (N+1)-th file is the only honest way to
        // KNOW the gallery was bigger than the cap - "items == maxItems" alone cannot
        // tell "exactly N" from "N of 400". The extra file is deleted before delivery.
        List<String> command = new ArrayList<>(List.of(
                tools.galleryDl().command(),
                "--directory", request.workDir().toString(),
                "--range", "1-" + (request.maxItems() + 1),
                "--no-mtime",
                "--filename-format", "{num|0:>3.{length}}{title[:%d]}.{extension}"
                        .formatted(NAME_LENGTH_LIMIT),
                request.url().toString()));

        if (options.proxy() != null && !options.proxy().isBlank()) {
            command.add("--proxy");
            command.add(options.proxy());
        }

        // Operator cookies only for requests allowed to use them (owner-only default).
        if (request.allowCookies() && options.cookiesFile() != null
                && !options.cookiesFile().isBlank()) {
            command.add("--cookies");
            command.add(options.cookiesFile());
        }

        AtomicInteger done = new AtomicInteger();

        ProcessRunner.Result result = new ProcessRunner(options.timeout())
                .run(command, request.workDir(), line -> {

                    // gallery-dl prints one line per finished file; count them for
                    // indeterminate progress ("7 of ~N files").
                    if (line != null && !line.startsWith("*") && !line.isBlank()) {
                        request.progress().onProgress(null, "files", done.incrementAndGet(), 0);
                    }

                });

        if (result.cancelled()) {
            throw new ExtractionException(Category.CANCELLED, BACKEND_ID, "Download cancelled");
        }

        if (result.timedOut()) {
            throw new ExtractionException(Category.TIMEOUT, BACKEND_ID,
                    "gallery-dl timed out after " + options.timeout().toMinutes() + " min");
        }

        // Scan one past the cap to detect the overflow, then trim and delete it.
        List<ExtractedItem> scanned = scanWorkDir(request.workDir(), request.maxItems() + 1);

        boolean truncated = scanned.size() > request.maxItems();
        List<ExtractedItem> items = truncated
                ? new ArrayList<>(scanned.subList(0, request.maxItems()))
                : scanned;

        if (truncated) {

            for (ExtractedItem extra : scanned.subList(request.maxItems(), scanned.size())) {

                try {
                    java.nio.file.Files.deleteIfExists(extra.file());
                } catch (IOException e) {
                    log.debug("Cannot remove the overflow file {}: {}", extra.file(), e.getMessage());
                }

            }

        }

        if (!result.success()) {

            ExtractionException failure = classify(result, request.url().toString());

            // Partial results are still results: a gallery where image 12 of 40 404s
            // should deliver the 11 that worked, with the failure logged.
            if (items.isEmpty()) {
                throw failure;
            }

            log.warn("gallery-dl exited {} with {} partial file(s) for {}: {}",
                    result.exitCode(), items.size(), request.url(), failure.getMessage());

        }

        if (items.isEmpty()) {
            throw new ExtractionException(Category.NOT_FOUND, BACKEND_ID,
                    "gallery-dl produced no files for " + request.url());
        }

        return new ExtractionResult(request.source(), BACKEND_ID, items,
                null, null, request.url().toString(), truncated);

    }

    private List<ExtractedItem> scanWorkDir(Path workDir, int maxItems) {

        List<ExtractedItem> items = new ArrayList<>();

        try (var stream = Files.walk(workDir)) {

            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        return !name.endsWith(".part") && !name.startsWith(".");
                    })
                    .sorted(Comparator.comparing(p -> p.toString()))
                    .toList();

            for (Path file : files) {

                if (items.size() >= maxItems) {
                    break;
                }

                long size;

                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    continue;
                }

                if (size == 0) {
                    continue;
                }

                items.add(ExtractedItem.of(MediaKind.fromExtension(
                        file.getFileName().toString()), file));

            }

        } catch (IOException e) {
            log.warn("Cannot scan gallery-dl work dir {}: {}", workDir, e.getMessage());
        }

        return items;

    }

    static ExtractionException classify(ProcessRunner.Result result, String url) {

        String text = (String.join("\n", result.stderrTail()) + "\n"
                + String.join("\n", result.stdoutTail())).toLowerCase(Locale.ROOT);
        String errorLine = result.errorLine() != null ? result.errorLine() : url;

        if (text.contains("authorization") || text.contains("login") || text.contains("cookies")
                || text.contains("private") && text.contains("account")
                || text.contains("401") || text.contains("403")) {
            return new ExtractionException(Category.LOGIN_REQUIRED, BACKEND_ID, errorLine);
        }

        if (text.contains("not found") || text.contains("404") || text.contains("does not exist")
                || text.contains("deleted")) {
            return new ExtractionException(Category.NOT_FOUND, BACKEND_ID, errorLine);
        }

        if (text.contains("unsupported")) {
            return new ExtractionException(Category.UNSUPPORTED, BACKEND_ID, errorLine);
        }

        if (text.contains("429") || text.contains("rate") || text.contains("too many")) {
            return new ExtractionException(Category.RATE_LIMITED, BACKEND_ID, errorLine);
        }

        if (text.contains("timeout") || text.contains("connection") || text.contains("network")
                || text.contains("ssl") || text.contains("resolve")) {
            return new ExtractionException(Category.NETWORK, BACKEND_ID, errorLine);
        }

        return new ExtractionException(Category.UNKNOWN, BACKEND_ID, errorLine);

    }

}
