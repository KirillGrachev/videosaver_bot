package eu.neydev.saver.core.command;

import eu.neydev.saver.core.TestBot;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.media.MediaKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user-visible dialog contract, end to end: a pasted link becomes a queued ack,
 * a delivered file and a done status; menus answer to commands and callbacks; junk
 * gets the hint, not a crash. Runs on {@link TestBot}: real router, real job manager,
 * fake platform.
 */
class CommandRouterTest {

    private static final PlatformUser USER = new PlatformUser(Platform.TELEGRAM, "42");

    private static IncomingUpdate.TextMessage text(String content) {
        return new IncomingUpdate.TextMessage(USER, "chat-1", content, "en");
    }

    @Test
    void startAnswersWithTheMainMenu(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 1_000)) {

            bot.router.accept(text("/start"));

            TestBot.waitUntil(() -> !bot.adapter.sent().isEmpty(), "start reply");

            assertThat(bot.adapter.sentOfType(OutboundMessage.Send.class)).isNotEmpty();

        }

    }

    @Test
    void aPastedLinkIsDownloadedAndDelivered(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 1_000)) {

            bot.router.accept(text("смотри https://youtube.com/watch?v=abc123"));

            TestBot.waitUntil(() -> bot.adapter.sentOfType(OutboundMessage.SendMedia.class)
                    .size() == 1, "the delivered file");

            // The lifecycle: ack, media, done status - three dialog lines, one file.
            OutboundMessage.SendMedia media =
                    bot.adapter.sentOfType(OutboundMessage.SendMedia.class).get(0);

            assertThat(media.media().kind()).isEqualTo(MediaKind.VIDEO);
            assertThat(media.chatId()).isEqualTo("chat-1");

            TestBot.waitUntil(() -> bot.storage.jobs().totalJobs() == 1
                            && !bot.storage.jobs().recent(USER, 1).get(0).status().equals("RUNNING"),
                    "the job to finish");

            assertThat(bot.storage.jobs().recent(USER, 1).get(0).status()).isEqualTo("SUCCEEDED");
            assertThat(bot.storage.jobs().recent(USER, 1).get(0).sourceId()).isEqualTo("youtube");
            assertThat(bot.storage.usage().get(USER,
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay()).jobs())
                    .isEqualTo(1);

        }

    }

    @Test
    void multipleLinksInOneMessageEachBecomeAJob(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.PHOTO, 500)) {

            bot.router.accept(text("https://imgur.com/a.png and https://giphy.com/x.gif"));

            TestBot.waitUntil(() -> bot.storage.jobs().totalJobs() == 2, "two queued jobs");

        }

    }

    @Test
    void textWithoutALinkGetsTheHintNotTheHelpWall(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            bot.router.accept(text("привет как дела"));

            TestBot.waitUntil(() -> !bot.adapter.sent().isEmpty(), "hint reply");

            assertThat(bot.adapter.sentOfType(OutboundMessage.Send.class)).hasSize(1);
            assertThat(bot.storage.jobs().totalJobs()).isZero();

        }

    }

    @Test
    void unknownCommandsAreRejectedPolitely(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            bot.router.accept(text("/definitely-not-a-command"));

            TestBot.waitUntil(() -> !bot.adapter.sent().isEmpty(), "rejection");

            assertThat(bot.adapter.sentOfType(OutboundMessage.Send.class)).hasSize(1);

        }

    }

    @Test
    void callbacksAnswerAndNavigate(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            bot.router.accept(new IncomingUpdate.Callback(USER, "chat-1",
                    Actions.SETTINGS, "msg-9", "int-9", "en"));

            TestBot.waitUntil(() -> bot.adapter.sentOfType(
                    OutboundMessage.AnswerCallback.class).size() == 1, "callback ack");

            // The pressed message is EDITED (menu navigation without spam).
            TestBot.waitUntil(() -> !bot.adapter.sentOfType(OutboundMessage.Edit.class)
                    .isEmpty(), "settings edit");

            assertThat(bot.adapter.sentOfType(OutboundMessage.Edit.class).get(0).messageId())
                    .isEqualTo("msg-9");

        }

    }

    @Test
    void qualityChoicePersistsAndAppliesToTheNextJob(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            bot.router.accept(new IncomingUpdate.Callback(USER, "chat-1",
                    Actions.QUALITY_SET_PREFIX + "720", "msg-1", "int-1", "en"));

            TestBot.waitUntil(() -> bot.storage.users().find(USER)
                            .map(s -> s.quality() == eu.neydev.saver.core.extract.QualityPreset.P720)
                            .orElse(false),
                    "quality persisted");

        }

    }

    @Test
    void stopCancelsTheActiveDownload(@TempDir Path dir) {

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, 2_000_000_000L, TestBot.defaultDelivery(),
                        1_000_000_000L),
                List.of(TestBot.slowExtractor()))) {

            bot.router.accept(text("https://youtube.com/watch?v=slow"));

            TestBot.waitUntil(() -> bot.jobManager.activeCount() == 1, "job to start");

            bot.router.accept(text("/stop"));

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 1).get(0)
                    .status().equals("CANCELLED"), "cancelled status");

            assertThat(bot.adapter.sentOfType(OutboundMessage.SendMedia.class)).isEmpty();

        }

    }

    @Test
    void theConcurrentLimitRefusesExtraJobs(@TempDir Path dir) {

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 1, 20, 2_000_000_000L, TestBot.defaultDelivery(),
                        1_000_000_000L),
                List.of(TestBot.slowExtractor()))) {

            bot.router.accept(text("https://youtube.com/watch?v=one"));

            TestBot.waitUntil(() -> bot.jobManager.activeCount() == 1, "first job");

            bot.router.accept(text("https://youtube.com/watch?v=two"));

            // The refusal is queued synchronously by accept(); wait for the dispatcher
            // to hand it to the fake adapter: ack for job one + refusal for job two.
            TestBot.waitUntil(() -> bot.adapter.sentOfType(OutboundMessage.Send.class)
                    .size() >= 2, "the refusal message");

            // The second request is refused: exactly one job in storage.
            assertThat(bot.storage.jobs().totalJobs()).isEqualTo(1);

        }

    }

    @Test
    void theSourcesBrowserPagesAndSearches(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            bot.router.accept(text("/sources"));

            TestBot.waitUntil(() -> !bot.adapter.sent().isEmpty(), "sources page");

            bot.router.accept(new IncomingUpdate.Callback(USER, "chat-1",
                    Actions.SOURCES_SEARCH, "msg-2", "int-2", "en"));
            bot.router.accept(text("tiktok"));

            TestBot.waitUntil(() -> bot.adapter.sentOfType(OutboundMessage.Send.class)
                    .size() >= 2, "search result");

        }

    }

    @Test
    void severalRefusalsCollapseIntoOneMessage(@TempDir Path dir) {

        // hourQuota = 1: of two links in one message the first is accepted and the
        // second refused - the user must see ONE refusal line, not one per link.
        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 4, 1, 2_000_000_000L, TestBot.defaultDelivery(),
                        1_000_000_000L),
                List.of(TestBot.slowExtractor()))) {

            bot.router.accept(text(
                    "https://youtube.com/watch?v=one https://youtube.com/watch?v=two"));

            TestBot.waitUntil(() -> bot.adapter.sentOfType(OutboundMessage.Send.class)
                    .size() >= 2, "ack + aggregated refusal");

            // Exactly one ack and exactly one (aggregated) refusal.
            assertThat(bot.adapter.sentOfType(OutboundMessage.Send.class)).hasSize(2);
            assertThat(bot.storage.jobs().totalJobs()).isEqualTo(1);

        }

    }

    @Test
    void perRequestQualityDoesNotTouchTheUserDefault(@TempDir Path dir) {

        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var last = new java.util.concurrent.atomic.AtomicReference<
                eu.neydev.saver.core.extract.ExtractionRequest>();

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, 2_000_000_000L, TestBot.defaultDelivery(),
                        1_000_000_000L),
                List.of(TestBot.recordingExtractor(MediaKind.VIDEO, 100, calls, last)))) {

            bot.router.accept(text("/save audio https://youtube.com/watch?v=x"));

            TestBot.waitUntil(() -> calls.get() == 1, "extraction to run");

            assertThat(last.get().quality())
                    .isEqualTo(eu.neydev.saver.core.extract.QualityPreset.AUDIO);

            // The user's stored default is untouched by a one-off request.
            assertThat(bot.storage.users().find(USER).orElseThrow().quality())
                    .isEqualTo(eu.neydev.saver.core.extract.QualityPreset.BEST);

        }

    }

    @Test
    void adminCommandsAreOwnerOnly(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            bot.router.accept(text("/admin"));

            TestBot.waitUntil(() -> !bot.adapter.sent().isEmpty(), "denial");

            assertThat(bot.metrics.count("admin_denied_total", "platform", "telegram"))
                    .isEqualTo(1);

        }

    }

}
