package eu.neydev.saver.core.command;

/**
 * Canonical action identifiers (callback data). Short and stable: VK has a payload
 * limit, Telegram - 64 bytes for callback_data.
 */
public final class Actions {

    public static final String BACK = "bk";
    public static final String SETTINGS = "st";
    public static final String ABOUT = "ab";
    public static final String HELP = "hp";
    public static final String STATS = "sx";

    /** Opens the quality picker; choices use {@link #QUALITY_SET_PREFIX}. */
    public static final String QUALITY_MENU = "qm";
    /** A quality choice: {@code q:720}. */
    public static final String QUALITY_SET_PREFIX = "q:";

    /** Opens the language picker; choices use {@link #LANG_SET_PREFIX}. */
    public static final String LANG_MENU = "lm";
    /** A language choice: {@code lg:ru}. */
    public static final String LANG_SET_PREFIX = "lg:";
    /** A page of the language picker: {@code lp:1} (zero-based). */
    public static final String LANG_PAGE_PREFIX = "lp:";

    /** Opens the sources browser (page 0). */
    public static final String SOURCES = "sm";
    /** A page of the sources browser: {@code sp:2} (zero-based). */
    public static final String SOURCES_PAGE_PREFIX = "sp:";
    /** Switches the sources browser into the search prompt. */
    public static final String SOURCES_SEARCH = "ss";
    /** A page of search results: {@code sr:1}; the query lives in the handler session. */
    public static final String SEARCH_PAGE_PREFIX = "sr:";

    /** Cancels the user's active downloads (the button on the queued status message). */
    public static final String JOB_STOP = "jp";

    public static final String RELOAD = "ar";
    public static final String ADMIN = "ad";
    public static final String NOOP = "np";

    private Actions() {
    }

    public static boolean hasPrefix(String actionId, String prefix) {
        return actionId != null && actionId.startsWith(prefix);
    }

    public static String suffix(String actionId, String prefix) {
        return actionId.substring(prefix.length());
    }

    /** The page number of a picker action; anything unparsable opens the first page. */
    public static int pageOf(String actionId, String prefix) {

        try {
            return Math.max(0, Integer.parseInt(actionId.substring(prefix.length())));
        } catch (RuntimeException e) {
            return 0;
        }

    }

}
