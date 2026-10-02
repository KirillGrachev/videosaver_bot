package eu.neydev.saver.core.source;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Which extraction technology a source is routed to FIRST. The chain always keeps
 * fallbacks (a gallery site whose post is a video still gets its video via yt-dlp),
 * so the backend is a hint that saves one failed attempt, not a verdict.
 */
public enum SourceBackend {

    /** yt-dlp: video platforms and anything with 1000+ community-maintained extractors. */
    YTDLP("ytdlp"),
    /** gallery-dl: image galleries, art communities, albums, multi-post pages. */
    GALLERYDL("gallerydl"),
    /** Built-in OpenGraph/JSON-LD scraper: pages with plain og:video / og:image meta. */
    HTTP("http"),
    /** The URL already points at a media file (CDN links, direct images). */
    DIRECT("direct");

    private final String id;

    SourceBackend(String id) {
        this.id = id;
    }

    public @NotNull String id() {
        return id;
    }

    public static SourceBackend fromId(String id) {

        for (SourceBackend backend : values()) {
            if (backend.id.equalsIgnoreCase(id.trim())) {
                return backend;
            }
        }

        throw new IllegalArgumentException("Unknown backend: " + id);

    }

    public static SourceBackend fromIdOrDefault(String id, SourceBackend fallback) {

        try {
            return fromId(id.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }

    }

}
