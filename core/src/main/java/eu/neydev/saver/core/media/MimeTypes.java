package eu.neydev.saver.core.media;

import java.util.Locale;
import java.util.Map;

/**
 * Extension -> MIME mapping for outbound media. Deliberately hand-rolled instead of
 * {@code Files.probeContentType}: that one consults the OS (and returns null on a slim
 * container for half the formats), while platforms need a Content-Type they accept.
 * Unknown extensions fall back to {@code application/octet-stream}, which every
 * platform treats as a generic document.
 */
public final class MimeTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("heic", "image/heic"),
            Map.entry("heif", "image/heif"),
            Map.entry("avif", "image/avif"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("m4v", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("3gp", "video/3gpp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("aac", "audio/aac"),
            Map.entry("opus", "audio/opus"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("oga", "audio/ogg"),
            Map.entry("weba", "audio/webm"),
            Map.entry("flac", "audio/flac"),
            Map.entry("wav", "audio/wav"),
            Map.entry("aiff", "audio/aiff"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("txt", "text/plain"),
            Map.entry("json", "application/json"),
            Map.entry("srt", "application/x-subrip"));

    private MimeTypes() {
    }

    public static String of(String fileName) {

        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";

        return BY_EXTENSION.getOrDefault(ext, "application/octet-stream");

    }

    /** Reverse lookup used by the generic HTTP extractor when only a Content-Type is known. */
    public static String extensionFor(String mimeType) {

        for (Map.Entry<String, String> entry : BY_EXTENSION.entrySet()) {
            if (entry.getValue().equals(mimeType)) {
                return entry.getKey();
            }
        }

        return "bin";

    }

}
