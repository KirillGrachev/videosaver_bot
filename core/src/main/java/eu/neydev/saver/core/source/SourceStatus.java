package eu.neydev.saver.core.source;

/**
 * Honest support level shown to users in /sources. Extractors for social platforms
 * break whenever the platform ships a redesign, and some content is simply unreachable
 * without an account - the catalog says so instead of promising everything works.
 */
public enum SourceStatus {

    /** A maintained extractor exists; the common public cases work. */
    OK("ok"),
    /** Works only for some links (age/region walls, frequent upstream breakage). */
    LIMITED("limited"),
    /** Needs an account/cookies or is closed behind an app; expect refusals. */
    LOGIN("login");

    private final String id;

    SourceStatus(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SourceStatus fromId(String id) {

        for (SourceStatus status : values()) {
            if (status.id.equalsIgnoreCase(id.trim())) {
                return status;
            }
        }

        throw new IllegalArgumentException("Unknown status: " + id);

    }

    public static SourceStatus fromIdOrDefault(String id, SourceStatus fallback) {

        try {
            return fromId(id);
        } catch (IllegalArgumentException e) {
            return fallback;
        }

    }

}
