package eu.neydev.saver.core.api;

import org.jetbrains.annotations.Nullable;

/**
 * What the platform answered to a successful {@link PlatformAdapter#execute}. Carries the
 * native message id when the platform reports one (all six do), so the job manager can
 * EDIT its own status message ("downloading... 42%") instead of spamming a new one.
 * {@code null} means "sent, id unknown" - fire-and-forget callers ignore the whole record.
 */
public record SendResult(@Nullable String messageId) {

    private static final SendResult ACK = new SendResult(null);

    /** A successful send without a usable message id. */
    public static SendResult ack() {
        return ACK;
    }

    public static SendResult of(String messageId) {
        return messageId == null || messageId.isBlank() ? ACK : new SendResult(messageId);
    }

}
