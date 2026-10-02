package eu.neydev.saver.core.media;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One deliverable file on its way to a platform. Immutable snapshot: the vault guarantees
 * the file exists at construction time and lives until the send completes (or the grace
 * period expires). {@code publicUrl} is filled ONLY for platforms that deliver media
 * by link (Viber) and only when the webapp has a public address; {@code sourceUrl} is
 * where the file came from - the fallback link when the file itself does not fit.
 */
public record MediaAttachment(@NotNull MediaKind kind,
                              @NotNull Path file,
                              @NotNull String fileName,
                              @NotNull String mimeType,
                              long sizeBytes,
                              @Nullable Integer width,
                              @Nullable Integer height,
                              @Nullable Integer durationSeconds,
                              @Nullable String title,
                              @Nullable String sourceUrl,
                              @Nullable String publicUrl) {

    public MediaAttachment {

        if (fileName.isBlank()) {
            throw new IllegalArgumentException("fileName cannot be blank");
        }

        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative: " + sizeBytes);
        }

    }

    /** Builds an attachment from a real file: size and mime are read from disk. */
    public static MediaAttachment of(MediaKind kind, Path file, @Nullable String title,
                                     @Nullable Integer width, @Nullable Integer height,
                                     @Nullable Integer durationSeconds, @Nullable String sourceUrl) {

        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a file: " + file);
        }

        long size;

        try {
            size = Files.size(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot stat " + file, e);
        }

        return new MediaAttachment(kind, file, file.getFileName().toString(),
                MimeTypes.of(file.getFileName().toString()), size,
                width, height, durationSeconds, title, sourceUrl, null);

    }

    /** A copy with the media kind changed (oversized video -> document) keeping everything else. */
    public MediaAttachment withKind(MediaKind newKind) {
        return new MediaAttachment(newKind, file, fileName, mimeType, sizeBytes,
                width, height, durationSeconds, title, sourceUrl, publicUrl);
    }

    /** A copy carrying a publicly reachable URL - for platforms that fetch media by link. */
    public MediaAttachment withPublicUrl(String url) {
        return new MediaAttachment(kind, file, fileName, mimeType, sizeBytes,
                width, height, durationSeconds, title, sourceUrl, url);
    }

    /**
     * A copy with metadata gaps filled (never overwritten): the ffprobe result steps
     * in only where the page/extractor knew nothing - Viber needs a duration for a
     * video message, Telegram needs geometry for a proper player.
     */
    public MediaAttachment withMissingMetadata(@Nullable Integer probedDuration,
                                               @Nullable Integer probedWidth,
                                               @Nullable Integer probedHeight) {

        return new MediaAttachment(kind, file, fileName, mimeType, sizeBytes,
                width != null ? width : probedWidth,
                height != null ? height : probedHeight,
                durationSeconds != null ? durationSeconds : probedDuration,
                title, sourceUrl, publicUrl);

    }

}
