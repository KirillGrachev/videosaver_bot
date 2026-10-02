package eu.neydev.saver.plugin.bukkit;

import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.command.CommandCatalog;
import eu.neydev.saver.core.text.RichText;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure mapping between the in-game chat and the core's normalized formats. No Bukkit
 * types in here - this class carries all the plugin's brain and is fully unit-tested.
 *
 * <p>Inbound convention (documented in plugin.yml/README): the bot only reacts to
 * messages starting with its prefix (default {@code !}), so normal player chat is never
 * touched. After the prefix:
 * <ul>
 *   <li>{@code !start}, {@code !save <url>}, {@code !dl <url>} ... - any name or alias
 *       from {@link CommandCatalog} is rewritten to the core's slash form;</li>
 *   <li>{@code !cb <actionId>} - a menu button press (buttons are rendered as
 *       {@code label - !cb actionId} lines, Minecraft chat has no inline keyboards);</li>
 *   <li>anything else (e.g. a bare URL) passes through as free text - the core's URL
 *       intake picks links up exactly like in the messengers.</li>
 * </ul>
 */
public final class BukkitChatMapper {

    /** What a prefixed chat message turned out to be. */
    public sealed interface Parsed {

        /** A catalog command in the core's slash form, e.g. {@code /save <url>}. */
        record Command(String coreText) implements Parsed {
        }

        /** A rendered menu button press. */
        record CallbackPress(String actionId) implements Parsed {
        }

        /** Free text (typically a bare URL) - handed to the core as-is. */
        record FreeText(String text) implements Parsed {
        }

    }

    /** The pseudo-command that carries button presses: {@code !cb <actionId>}. */
    public static final String CALLBACK_WORD = "cb";

    private BukkitChatMapper() {
    }

    public static Optional<Parsed> parse(String prefix, String raw) {

        if (raw == null || !raw.startsWith(prefix)) {
            return Optional.empty();
        }

        String rest = raw.substring(prefix.length()).strip();

        if (rest.isEmpty()) {
            return Optional.empty();
        }

        // An explicit slash form wins verbatim ("!/start").
        if (rest.startsWith("/")) {
            return Optional.of(new Parsed.Command(rest));
        }

        String first = firstToken(rest);

        if (first.equalsIgnoreCase(CALLBACK_WORD)) {

            String actionId = rest.substring(first.length()).strip();
            return actionId.isEmpty() || actionId.contains(" ")
                    ? Optional.empty()
                    : Optional.of(new Parsed.CallbackPress(actionId));

        }

        if (CommandCatalog.resolve(first.toLowerCase(Locale.ROOT)).isPresent()) {
            return Optional.of(new Parsed.Command("/" + rest));
        }

        return Optional.of(new Parsed.FreeText(rest));

    }

    /**
     * RichText + keyboard -> one plain chat string. Buttons become hint lines
     * ({@code > label - !cb actionId} / {@code > label: https://...}) because the
     * in-game chat has nothing clickable.
     */
    public static String render(String prefix, RichText text, InlineKeyboard keyboard) {

        StringBuilder out = new StringBuilder(text.toPlainText());

        for (List<InlineKeyboard.KeyboardButton> row : keyboard.rows()) {
            for (InlineKeyboard.KeyboardButton button : row) {

                out.append('\n').append("> ").append(button.label());

                if (button.url() != null) {
                    out.append(": ").append(button.url());
                } else {
                    out.append(" - ").append(prefix).append(CALLBACK_WORD)
                            .append(' ').append(button.actionId());
                }

            }
        }

        return out.toString();

    }

    private static String firstToken(String text) {

        int i = 0;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }

        return text.substring(0, i);

    }

}
