package eu.neydev.saver.platform.telegram;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import org.jetbrains.annotations.Nullable;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.Optional;

/**
 * Update -> a normalized core event. Private chats and groups are supported;
 * service updates (edited, channel_post etc.) are deliberately ignored.
 */
public final class TelegramUpdateMapper {

    private TelegramUpdateMapper() {
    }

    public static Optional<IncomingUpdate> map(Update update) {

        if (update.hasCallbackQuery()) {
            return mapCallback(update.getCallbackQuery());
        }

        if (update.hasMessage()) {
            return mapMessage(update.getMessage());
        }

        return Optional.empty();

    }

    private static Optional<IncomingUpdate> mapMessage(Message message) {

        if (message.getFrom() == null || message.getText() == null || message.getText().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new IncomingUpdate.TextMessage(
                user(message.getFrom().getId()),
                chatId(message.getChatId()),
                message.getText(),
                message.getFrom().getLanguageCode()));

    }

    private static Optional<IncomingUpdate> mapCallback(CallbackQuery query) {

        if (query.getData() == null || query.getMessage() == null) {
            return Optional.empty();
        }

        return Optional.of(new IncomingUpdate.Callback(
                user(query.getFrom().getId()),
                chatId(query.getMessage().getChatId()),
                query.getData(),
                String.valueOf(query.getMessage().getMessageId()),
                query.getId(),
                query.getFrom().getLanguageCode()));

    }

    private static PlatformUser user(Long id) {
        return new PlatformUser(Platform.TELEGRAM, Long.toString(id));
    }

    /** The core chatId = the native Telegram chat id. */
    private static String chatId(Long chatId) {
        return String.valueOf(chatId);
    }

    public static long nativeChatId(String chatId) {
        return Long.parseLong(chatId);
    }

    public static int nativeMessageId(String messageId) {
        return Integer.parseInt(messageId);
    }

    public static @Nullable String safeText(Message message) {
        return message == null ? null : message.getText();
    }

}

