package eu.neydev.saver.core.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A platform-independent inline keyboard: rows of buttons, each button has
 * an already localized caption (the core substitutes it via i18n) and either an action
 * (actionId from YAML), or a url. The adapter only translates the structure into its own format.
 */
public record InlineKeyboard(@NotNull List<List<KeyboardButton>> rows) {

    private static final InlineKeyboard EMPTY = new InlineKeyboard(List.of());

    public InlineKeyboard {
        rows = List.copyOf(rows);
    }

    public static InlineKeyboard empty() {
        return EMPTY;
    }

    @SafeVarargs
    public static InlineKeyboard of(List<KeyboardButton>... rows) {

        List<List<KeyboardButton>> copy = new java.util.ArrayList<>(rows.length);
        for (List<KeyboardButton> row : rows) copy.add(row);

        return new InlineKeyboard(copy);

    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * @param label    localized button text;
     * @param actionId platform-independent action (mutually exclusive with url);
     * @param url      external link (mutually exclusive with actionId);
     * @param style    styling hint for platforms with colored buttons.
     */
    public record KeyboardButton(@NotNull String label,
                                 @Nullable String actionId,
                                 @Nullable String url,
                                 @NotNull Style style) {

        public KeyboardButton {
            if ((actionId == null) == (url == null)) {
                throw new IllegalArgumentException(
                        "A button must have exactly one of: actionId, url (label=" + label + ")");
            }
        }

        public static KeyboardButton callback(String label, String actionId) {
            return new KeyboardButton(label, actionId, null, Style.SECONDARY);
        }

        public static KeyboardButton callback(String label, String actionId, Style style) {
            return new KeyboardButton(label, actionId, null, style);
        }

        public static KeyboardButton url(String label, String url) {
            return new KeyboardButton(label, null, url, Style.SECONDARY);
        }

        public enum Style { PRIMARY, SECONDARY, DANGER }

    }

}

