package eu.neydev.saver.core.extract;

import java.util.Locale;

/**
 * What the user asked for. A preset, not a format string: the same "720p" means
 * different yt-dlp selectors depending on whether ffmpeg is installed, and audio-only
 * degrades to the raw best-audio stream instead of failing when transcoding is missing.
 */
public enum QualityPreset {

    BEST("best"),
    P1080("1080"),
    P720("720"),
    P480("480"),
    AUDIO("audio");

    private final String id;

    QualityPreset(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static QualityPreset fromId(String id) {

        for (QualityPreset preset : values()) {
            if (preset.id.equalsIgnoreCase(id.trim())) {
                return preset;
            }
        }

        throw new IllegalArgumentException("Unknown quality preset: " + id);

    }

    public static QualityPreset fromIdOrDefault(String id, QualityPreset fallback) {

        try {
            return fromId(id.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }

    }

}
