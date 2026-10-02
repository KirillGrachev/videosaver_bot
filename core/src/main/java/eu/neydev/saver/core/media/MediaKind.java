package eu.neydev.saver.core.media;

/**
 * What a downloaded file IS, platform-independently. The platform adapters map the kind
 * to their native message type (Telegram sendVideo / Discord attachment / VK video...),
 * and the size policy uses it to pick the delivery limits: a 60 MB video is rejected
 * on Telegram while a 60 MB document still passes.
 */
public enum MediaKind {

    PHOTO("photo"),
    VIDEO("video"),
    AUDIO("audio"),
    /** Animated image (GIF): Telegram has a native animation type, others get a file. */
    GIF("gif"),
    /** Anything else: archives, subtitles, originals the platform cannot preview. */
    DOCUMENT("document");

    private final String id;

    MediaKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static MediaKind fromId(String id) {

        for (MediaKind kind : values()) {
            if (kind.id.equalsIgnoreCase(id)) {
                return kind;
            }
        }

        throw new IllegalArgumentException("Unknown media kind: " + id);

    }

    /** Guess from a file extension; unknown extensions become DOCUMENT, never a crash. */
    public static MediaKind fromExtension(String fileName) {

        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";

        return switch (ext) {

            case "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif" -> PHOTO;
            case "gif" -> GIF;
            case "mp4", "webm", "mkv", "mov", "avi", "m4v", "3gp", "flv", "wmv", "ts", "mpg", "mpeg" -> VIDEO;
            case "mp3", "m4a", "aac", "opus", "ogg", "oga", "weba", "flac", "wav", "aiff", "alac" -> AUDIO;
            default -> DOCUMENT;

        };

    }

}
