package eu.neydev.saver.core.extract;

/**
 * A classified extraction failure. The category drives the user-facing message: "this
 * video is private" is actionable, "error 1" is not. Classification happens where the
 * knowledge is - inside each backend, from the tool's own ERROR lines and HTTP codes -
 * and the chain picks the MOST SPECIFIC category when every backend failed.
 */
public class ExtractionException extends RuntimeException {

    public enum Category {

        /** The site/URL is not something the tools can handle. */
        UNSUPPORTED,
        /** Content exists but needs an account/cookies. */
        LOGIN_REQUIRED,
        AGE_RESTRICTED,
        /** Private/deleted-by-author content. */
        PRIVATE,
        NOT_FOUND,
        GEO_BLOCKED,
        LIVE_STREAM,
        RATE_LIMITED,
        TOO_LARGE,
        TIMEOUT,
        /** yt-dlp/gallery-dl/ffmpeg binary missing - an operator problem, not a user one. */
        TOOL_MISSING,
        NETWORK,
        CANCELLED,
        UNKNOWN;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

    }

    /** Higher = more specific; used to choose which backend's error to report. */
    private static int specificity(Category category) {

        return switch (category) {

            case LOGIN_REQUIRED -> 90;
            case AGE_RESTRICTED -> 85;
            case PRIVATE -> 80;
            case GEO_BLOCKED -> 75;
            case NOT_FOUND -> 70;
            case LIVE_STREAM -> 65;
            case TOO_LARGE -> 60;
            case RATE_LIMITED -> 50;
            case TIMEOUT -> 40;
            case NETWORK -> 30;
            case UNSUPPORTED -> 20;
            case TOOL_MISSING -> 15;
            case CANCELLED -> 10;
            case UNKNOWN -> 0;

        };

    }

    private final Category category;
    private final String backendId;

    public ExtractionException(Category category, String backendId, String message) {
        this(category, backendId, message, null);
    }

    public ExtractionException(Category category, String backendId, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.backendId = backendId;
    }

    public Category category() {
        return category;
    }

    public String backendId() {
        return backendId;
    }

    /** Retrying the same URL soon makes sense only for these. */
    public boolean retryable() {
        return category == Category.RATE_LIMITED || category == Category.NETWORK
                || category == Category.TIMEOUT;
    }

    /**
     * Chooses the error worth showing when several backends failed: the most specific
     * category, and within one category the first occurrence (the primary backend's
     * phrasing is usually the most on-point).
     */
    public static ExtractionException mostSpecific(java.util.List<ExtractionException> failures) {

        if (failures.isEmpty()) {
            return new ExtractionException(Category.UNKNOWN, "-", "no extraction attempted");
        }

        ExtractionException best = failures.get(0);

        for (ExtractionException candidate : failures) {

            if (specificity(candidate.category()) > specificity(best.category())) {
                best = candidate;
            }

        }

        return best;

    }

}
