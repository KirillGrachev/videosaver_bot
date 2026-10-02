package eu.neydev.saver.core.command;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.storage.UserSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The context of a single user interaction: the event + the loaded settings.
 * Callbacks carry a message id for Edit and a token for AnswerCallback - handlers
 * do not deal with unpacking platform types.
 */
public record Interaction(@NotNull IncomingUpdate update, @NotNull UserSettings settings,
                          @NotNull String args) {

    public Interaction(IncomingUpdate update, UserSettings settings) {
        this(update, settings, "");
    }

    public boolean isCallback() {
        return update instanceof IncomingUpdate.Callback;
    }

    /** Message id for Edit (menu navigation) or {@code null}. */
    public @Nullable String editMessageId() {
        return update instanceof IncomingUpdate.Callback callback ? callback.messageId() : null;
    }

    /** The button payload that produced this interaction, or {@code null} for typed text. */
    public @Nullable String actionId() {
        return update instanceof IncomingUpdate.Callback callback ? callback.actionId() : null;
    }

    /** The interaction token needed to answer a button press, or {@code null}. */
    public @Nullable String interactionId() {
        return update instanceof IncomingUpdate.Callback callback ? callback.interactionId() : null;
    }

    public String text() {
        return update instanceof IncomingUpdate.TextMessage message ? message.text() : "";
    }

    /** Everything after the command name ("/save URL" -> "URL"); empty for callbacks. */
    public String args() {
        return args;
    }

    public String chatId() {
        return update.chatId();
    }

    public Locale locale() {
        return Locale.forLanguageTag(settings.locale());
    }

}
