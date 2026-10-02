package eu.neydev.saver.core.media;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Magic-byte recognition for downloads that arrive WITHOUT a usable name: a CDN url
 * like {@code .../videoplayback?id=...} or {@code .../KLz17Yc9shs} carries no extension,
 * and a file delivered under such a name reaches the user as an extensionless document
 * card (no player, no preview, no hint what is inside). The first sixteen bytes of the
 * file itself are a far better witness than the url path, so the generic backends ask
 * this class and rename the file before anyone builds an attachment from it.
 *
 * <p>The table covers what the generic backends actually run into (pictures, video
 * containers, audio containers, pdf, zip); anything unrecognized stays extensionless -
 * a wrong extension is worse than none, it would lie about the content type.
 */
public final class MediaSniffer {

    private static final int HEADER_BYTES = 16;

    private MediaSniffer() {
    }

    /**
     * The extension the file header promises ({@code mp4}, {@code jpg}), or empty when
     * the header is not in the table. A read failure is "no opinion", never an error:
     * the download itself already succeeded and must still be delivered.
     */
    public static Optional<String> extension(Path file) {

        byte[] header;

        try (var in = Files.newInputStream(file)) {
            header = in.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            return Optional.empty();
        }

        if (header.length == 0) {
            return Optional.empty();
        }

        return Optional.ofNullable(match(header));

    }

    private static @Nullable String match(byte[] h) {

        if (startsWith(h, 0, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }

        if (startsWith(h, 0, 0x89, 0x50, 0x4E, 0x47)) {
            return "png";
        }

        if (startsWith(h, 0, 0x47, 0x49, 0x46, 0x38)) {
            return "gif";
        }

        if (startsWith(h, 0, 0x42, 0x4D)) {
            return "bmp";
        }

        if (startsWith(h, 0, 0x49, 0x44, 0x33) || (h.length > 1 && (h[0] & 0xFF) == 0xFF
                && (h[1] & 0xE0) == 0xE0)) {
            return "mp3";
        }

        if (startsWith(h, 0, 0x66, 0x4C, 0x61, 0x43)) {
            return "flac";
        }

        if (startsWith(h, 0, 0x4F, 0x67, 0x67, 0x53)) {
            return "ogg";
        }

        if (startsWith(h, 0, 0x25, 0x50, 0x44, 0x46)) {
            return "pdf";
        }

        if (startsWith(h, 0, 0x50, 0x4B, 0x03, 0x04)) {
            return "zip";
        }

        if (startsWith(h, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return "mkv";
        }

        // RIFF containers: the subtype sits at offset 8 (WEBP / AVI / WAVE).
        if (startsWith(h, 0, 0x52, 0x49, 0x46, 0x46)) {

            if (startsWith(h, 8, 0x57, 0x45, 0x42, 0x50)) {
                return "webp";
            }

            if (startsWith(h, 8, 0x41, 0x56, 0x49, 0x20)) {
                return "avi";
            }

            if (startsWith(h, 8, 0x57, 0x41, 0x56, 0x45)) {
                return "wav";
            }

            return null;

        }

        // ISO base media: "ftyp" at offset 4 covers mp4/m4a/m4v/mov; the brand that
        // follows decides little for delivery, they all map to the mp4 family.
        if (startsWith(h, 4, 0x66, 0x74, 0x79, 0x70)) {
            return "mp4";
        }

        return null;

    }

    private static boolean startsWith(byte[] header, int offset, int... bytes) {

        if (header.length < offset + bytes.length) {
            return false;
        }

        for (int i = 0; i < bytes.length; i++) {

            if ((header[offset + i] & 0xFF) != bytes[i]) {
                return false;
            }
        }

        return true;

    }

    /** True when the file name already carries an extension the platforms understand. */
    public static boolean hasExtension(String fileName) {

        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');

        return dot > 0 && dot < name.length() - 1;

    }

}
