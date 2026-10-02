package eu.neydev.saver.core.extract;

import eu.neydev.saver.core.source.Source;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A finished extraction: what was downloaded, by which backend, and the page-level
 * metadata for captions. {@code truncated} marks galleries/playlists cut to
 * {@code maxItems} - the completion message then says "5 of 42" instead of lying
 * that this is everything.
 */
public record ExtractionResult(@NotNull Source source,
                               @NotNull String backendId,
                               @NotNull List<ExtractedItem> items,
                               @Nullable String title,
                               @Nullable String uploader,
                               @Nullable String webpageUrl,
                               boolean truncated,
                               @Nullable String warningKey) {

    public ExtractionResult {
        items = List.copyOf(items);
    }

    /** Constructor without a warning - the common case. */
    public ExtractionResult(Source source, String backendId, List<ExtractedItem> items,
                            @Nullable String title, @Nullable String uploader,
                            @Nullable String webpageUrl, boolean truncated) {
        this(source, backendId, items, title, uploader, webpageUrl, truncated, null);
    }

    public long totalBytes() {
        return items.stream().mapToLong(ExtractedItem::sizeBytes).sum();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

}
