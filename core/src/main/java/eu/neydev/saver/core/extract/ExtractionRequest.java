package eu.neydev.saver.core.extract;

import eu.neydev.saver.core.source.Source;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;

/**
 * Everything one extraction attempt needs. The work directory is job-exclusive: an
 * extractor may treat "all files that appeared in it" as its result, which is far more
 * robust than parsing tool-specific "saved to" lines for every possible output shape
 * (merged streams, extracted audio, galleries with nested folders).
 *
 * <p>{@code allowCookies} is a per-REQUEST decision, not a global one: with
 * {@code downloader.cookies-owner-only} (the default) the operator's account cookies
 * are attached only to requests coming from owner keys, so a public bot never lends
 * the operator's logged-in identity to strangers.
 */
public record ExtractionRequest(@NotNull Source source,
                                @NotNull URI url,
                                @NotNull QualityPreset quality,
                                @NotNull Path workDir,
                                long maxFileBytes,
                                int maxItems,
                                @NotNull ProgressListener progress,
                                boolean allowCookies) {

    public ExtractionRequest {

        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }

        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }

    }

    /** Full constructor without the cookies flag - cookies allowed (single-operator bots). */
    public ExtractionRequest(Source source, URI url, QualityPreset quality, Path workDir,
                             long maxFileBytes, int maxItems, ProgressListener progress) {
        this(source, url, quality, workDir, maxFileBytes, maxItems, progress, true);
    }

    /** Convenience for tests and single-file tools. */
    public ExtractionRequest(Source source, URI url, QualityPreset quality, Path workDir) {
        this(source, url, quality, workDir, 500_000_000L, 10, ProgressListener.NOOP, true);
    }

    public @Nullable String urlString() {
        return url.toString();
    }

}
