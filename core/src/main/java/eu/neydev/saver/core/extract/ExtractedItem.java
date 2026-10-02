package eu.neydev.saver.core.extract;

import eu.neydev.saver.core.media.MediaKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * One downloaded file with the metadata the tools could tell us. {@code mediaUrl} is
 * the pre-download address when the extractor knows it (direct links, OpenGraph picks):
 * it powers the "too large for this platform, here is the link" fallback.
 */
public record ExtractedItem(@NotNull MediaKind kind,
                            @NotNull Path file,
                            long sizeBytes,
                            @Nullable String title,
                            @Nullable Integer width,
                            @Nullable Integer height,
                            @Nullable Integer durationSeconds,
                            @Nullable String mediaUrl) {

    public ExtractedItem {

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative");
        }

    }

    public static ExtractedItem of(MediaKind kind, Path file) {
        return new ExtractedItem(kind, file, sizeOf(file), null, null, null, null, null);
    }

    private static long sizeOf(Path file) {

        try {
            return java.nio.file.Files.size(file);
        } catch (java.io.IOException e) {
            return 0;
        }

    }

}
