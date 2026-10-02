package eu.neydev.saver.core.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import eu.neydev.saver.core.util.TraceId;

/**
 * A normalized inbound event stream. Platform adapters must reduce
 * its updates to one of these variants - the core knows nothing about the types
 * Telegram/VK/Discord.
 *
 * <p>Every event carries a {@code traceId} - a correlation id for end-to-end
 * logging an update's path through the pipeline, and a {@code localeHint} -
 * the language from the user's platform profile (may be absent).
 */
public sealed interface IncomingUpdate {

    PlatformUser user();

    String chatId();

    String traceId();

    /**
     * A text message or command (/start, /help, free text in a dialog).
     */
    record TextMessage(@NotNull PlatformUser user,
                       @NotNull String chatId,
                       @NotNull String text,
                       @Nullable String localeHint,
                       @NotNull String traceId) implements IncomingUpdate {
        public TextMessage(PlatformUser user, String chatId, String text, String localeHint) {
            this(user, chatId, text, localeHint, TraceId.newId());
        }
    }

    /**
     * An inline button press. {@code actionId} is the platform-independent identifier
     * actions from YAML keyboards; {@code interactionId} is the platform token
     * (callback_query.id / event_id / interaction id) to respond to the press.
     */
    record Callback(@NotNull PlatformUser user,
                    @NotNull String chatId,
                    @NotNull String actionId,
                    @NotNull String messageId,
                    @NotNull String interactionId,
                    @Nullable String localeHint,
                    @NotNull String traceId) implements IncomingUpdate {
        public Callback(PlatformUser user, String chatId, String actionId,
                        String messageId, String interactionId, String localeHint) {
            this(user, chatId, actionId, messageId, interactionId, localeHint, TraceId.newId());
        }
    }

    /**
     * Sending a modal form (Discord modal, web app, etc.).
     * {@code fields} keys are platform-independent field names.
     */
    record FormSubmit(@NotNull PlatformUser user,
                      @NotNull String chatId,
                      @NotNull String actionId,
                      @NotNull Map<String, String> fields,
                      @Nullable String localeHint,
                      @NotNull String traceId) implements IncomingUpdate {
        public FormSubmit(PlatformUser user, String chatId, String actionId,
                          Map<String, String> fields, String localeHint) {
            this(user, chatId, actionId, fields, localeHint, TraceId.newId());
        }
    }

}

