package eu.neydev.saver.core.api;

import eu.neydev.saver.core.media.MediaAttachment;
import eu.neydev.saver.core.text.RichText;
import eu.neydev.saver.core.util.TraceId;
import org.jetbrains.annotations.NotNull;

/**
 * A normalized outbound stream. The transport platform is a TYPE FIELD, not a prefix
 * strings: routing in the dispatcher is compiled, not parsed from the chatId.
 * {@code chatId} is the platform's native dialog identifier without any prefixes.
 */
public sealed interface OutboundMessage {

    Platform platform();

    String chatId();

    /** Correlation id of a chain (update or reminder), for end-to-end logs. */
    String traceId();

    /** Send a new message. */
    record Send(@NotNull Platform platform,
                @NotNull String chatId,
                @NotNull RichText text,
                @NotNull InlineKeyboard keyboard,
                boolean silent,
                @NotNull String traceId) implements OutboundMessage {
        public Send(Platform platform, String chatId, RichText text,
                    InlineKeyboard keyboard, boolean silent) {
            this(platform, chatId, text, keyboard, silent, TraceId.currentOrNew());
        }

        public Send(Platform platform, String chatId, RichText text, InlineKeyboard keyboard) {
            this(platform, chatId, text, keyboard, false, TraceId.currentOrNew());
        }
    }

    /**
     * Send one media file with a caption. The attachment points at a vault file that is
     * guaranteed to exist until the send settles; platforms that deliver by link (Viber)
     * use {@code media.publicUrl()} and fall back to the caption plus {@code sourceUrl}
     * when no public link was resolved.
     */
    record SendMedia(@NotNull Platform platform,
                     @NotNull String chatId,
                     @NotNull RichText caption,
                     @NotNull InlineKeyboard keyboard,
                     @NotNull MediaAttachment media,
                     boolean silent,
                     @NotNull String traceId) implements OutboundMessage {
        public SendMedia(Platform platform, String chatId, RichText caption,
                         InlineKeyboard keyboard, MediaAttachment media, boolean silent) {
            this(platform, chatId, caption, keyboard, media, silent, TraceId.currentOrNew());
        }

        public SendMedia(Platform platform, String chatId, RichText caption,
                         InlineKeyboard keyboard, MediaAttachment media) {
            this(platform, chatId, caption, keyboard, media, false, TraceId.currentOrNew());
        }
    }

    /**
     * Send several files as ONE album where the platform supports it (Telegram media
     * group). The caption rides on the group; the dispatcher expands the record into
     * individual {@link SendMedia} for platforms without native albums, so adapters
     * either implement groups honestly or never see them.
     */
    record SendMediaGroup(@NotNull Platform platform,
                          @NotNull String chatId,
                          @NotNull RichText caption,
                          @NotNull InlineKeyboard keyboard,
                          @NotNull java.util.List<eu.neydev.saver.core.media.MediaAttachment> media,
                          boolean silent,
                          @NotNull String traceId) implements OutboundMessage {
        public SendMediaGroup {
            if (media.isEmpty()) {
                throw new IllegalArgumentException("A media group needs at least one file");
            }
            media = java.util.List.copyOf(media);
        }

        public SendMediaGroup(Platform platform, String chatId, RichText caption,
                              InlineKeyboard keyboard,
                              java.util.List<eu.neydev.saver.core.media.MediaAttachment> media,
                              boolean silent) {
            this(platform, chatId, caption, keyboard, media, silent, TraceId.currentOrNew());
        }
    }

    /** Edit an existing message (menu navigation without spam). */
    record Edit(@NotNull Platform platform,
                @NotNull String chatId,
                @NotNull String messageId,
                @NotNull RichText text,
                @NotNull InlineKeyboard keyboard,
                @NotNull String traceId) implements OutboundMessage {
        public Edit(Platform platform, String chatId, String messageId,
                    RichText text, InlineKeyboard keyboard) {
            this(platform, chatId, messageId, text, keyboard, TraceId.currentOrNew());
        }
    }

    /** Delete a message (a service one, e.g. an input-prompt draft). */
    record Delete(@NotNull Platform platform,
                  @NotNull String chatId,
                  @NotNull String messageId,
                  @NotNull String traceId) implements OutboundMessage {
        public Delete(Platform platform, String chatId, String messageId) {
            this(platform, chatId, messageId, TraceId.currentOrNew());
        }
    }

    /**
     * Reply to a button press: a toast notification or clearing the "clock".
     * {@code interactionId} - the same token that arrived in {@link IncomingUpdate.Callback}.
     */
    record AnswerCallback(@NotNull Platform platform,
                          @NotNull String chatId,
                          @NotNull String interactionId,
                          @NotNull String text,
                          boolean showAlert,
                          @NotNull String traceId) implements OutboundMessage {
        public AnswerCallback(Platform platform, String chatId, String interactionId,
                              String text, boolean showAlert) {
            this(platform, chatId, interactionId, text, showAlert, TraceId.currentOrNew());
        }
    }

}

