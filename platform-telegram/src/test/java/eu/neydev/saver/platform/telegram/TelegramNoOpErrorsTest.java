package eu.neydev.saver.platform.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two Telegram 400s that mean "nothing to do" must never reach the retry ladder:
 * an edit to identical content and a toast for an expired callback window both arrive
 * right after a restart, when users press buttons the bot could not answer in time.
 */
class TelegramNoOpErrorsTest {

    private static TelegramApiRequestException error(String description) {

        return new TelegramApiRequestException("x",
                new ApiResponse<>(false, 400, description, null, null));

    }

    @Test
    void identicalEditIsANoOp() {

        assertThat(TelegramAdapter.isNoOp(error("Bad Request: message is not modified: specified new "
                + "message content and reply markup are exactly the same as a current content "
                + "and reply markup of the message"))).isTrue();

    }

    @Test
    void expiredCallbackToastIsANoOp() {

        assertThat(TelegramAdapter.isNoOp(error("Bad Request: query is too old and response timeout "
                + "expired or query ID is invalid"))).isTrue();

    }

    @Test
    void realFailuresStayFailures() {

        assertThat(TelegramAdapter.isNoOp(error("Bad Request: chat not found"))).isFalse();
        assertThat(TelegramAdapter.isNoOp(error("Unauthorized"))).isFalse();
        assertThat(TelegramAdapter.isNoOp(new TelegramApiException("network gone"))).isFalse();

    }
}

