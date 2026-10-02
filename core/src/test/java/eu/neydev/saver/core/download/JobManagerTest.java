package eu.neydev.saver.core.download;

import eu.neydev.saver.core.TestBot;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.util.ByteFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The download engine's refusals and size-policy decisions - everything that must be
 * decided BEFORE a byte hits the wire, tested against the real JobManager with fake
 * extractors.
 */
class JobManagerTest {

    private static final PlatformUser USER = new PlatformUser(Platform.TELEGRAM, "42");

    private static JobManager.SubmitOutcome submit(TestBot bot, String url) {
        return bot.jobManager.submit(USER, "chat-1", url, QualityPreset.BEST, Locale.ENGLISH);
    }

    @Test
    void invalidUrlsAreRefusedSynchronously(@TempDir Path dir) {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            assertThat(submit(bot, "ftp://example.com/file")).isInstanceOf(
                    JobManager.SubmitOutcome.InvalidUrl.class);
            assertThat(submit(bot, "hello world")).isInstanceOf(
                    JobManager.SubmitOutcome.InvalidUrl.class);

            assertThat(bot.storage.jobs().totalJobs()).isZero();

        }

    }

    @Test
    void theHourlyQuotaRefusesWithItsOwnOutcome(@TempDir Path dir) {

        // hourQuota = 1, one job already logged inside the window.
        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 1, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, 100)))) {

            bot.storage.jobs().insertQueued("old", USER, "https://old.example/x",
                    Instant.now().minus(Duration.ofMinutes(5)));

            JobManager.SubmitOutcome outcome = submit(bot, "https://youtube.com/watch?v=x");

            assertThat(outcome).isInstanceOf(JobManager.SubmitOutcome.Refused.class);
            assertThat(((JobManager.SubmitOutcome.Refused) outcome).rejection())
                    .isEqualTo(JobManager.Rejection.QUOTA_HOUR);

        }

    }

    @Test
    void theDailyBytesQuotaRefuses(@TempDir Path dir) {

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, 1_000L, TestBot.defaultDelivery(),
                        ByteFormat.parse("1gb", "t")),
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, 100)))) {

            bot.storage.usage().increment(USER,
                    LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC).toEpochDay(), 0, 1_000L);

            JobManager.SubmitOutcome outcome = submit(bot, "https://youtube.com/watch?v=x");

            assertThat(((JobManager.SubmitOutcome.Refused) outcome).rejection())
                    .isEqualTo(JobManager.Rejection.QUOTA_BYTES);

        }

    }

    @Test
    void disabledSourcesAreRefusedByName(@TempDir Path dir) {

        AppConfig base = TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t"));

        AppConfig config = new AppConfig(base.storage(), base.downloader(), base.limits(),
                base.delivery(), base.pipeline(), base.webApp(), base.locale(),
                new AppConfig.Sources(List.of("tiktok")), base.community(),
                base.platforms(), base.ownerKeys(), base.status());

        try (TestBot bot = TestBot.with(dir, config,
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, 100)))) {

            JobManager.SubmitOutcome outcome =
                    submit(bot, "https://www.tiktok.com/@user/video/1");

            assertThat(((JobManager.SubmitOutcome.Refused) outcome).rejection())
                    .isEqualTo(JobManager.Rejection.SOURCE_DISABLED);
            assertThat(((JobManager.SubmitOutcome.Refused) outcome).params())
                    .containsEntry("title", "TikTok");

        }

    }

    @Test
    void aFullVaultRefusesNewJobs(@TempDir Path dir) {

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), 10L),
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, 100)))) {

            assertThat(submit(bot, "https://youtube.com/watch?v=x"))
                    .isInstanceOf(JobManager.SubmitOutcome.Refused.class);

        }

    }

    @Test
    void oversizedVideosDegradeToDocuments(@TempDir Path dir) {

        // video cap 1 MB, document cap 10 MB, the "file" declares 5 MB.
        AppConfig.Delivery caps = new AppConfig.Delivery(Map.of(
                Platform.TELEGRAM, new AppConfig.Delivery.SizeCaps(
                        ByteFormat.parse("1mb", "t"), ByteFormat.parse("1mb", "t"),
                        ByteFormat.parse("10mb", "t"), ByteFormat.parse("10mb", "t"),
                        ByteFormat.parse("10mb", "t"))), true);

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"), caps,
                        ByteFormat.parse("1gb", "t")),
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, ByteFormat.parse("5mb", "t"))))) {

            assertThat(submit(bot, "https://youtube.com/watch?v=x"))
                    .isInstanceOf(JobManager.SubmitOutcome.Accepted.class);

            TestBot.waitUntil(() -> bot.adapter.sentOfType(OutboundMessage.SendMedia.class)
                    .size() == 1, "document delivery");

            assertThat(bot.adapter.sentOfType(OutboundMessage.SendMedia.class).get(0)
                    .media().kind()).isEqualTo(MediaKind.DOCUMENT);

        }

    }

    @Test
    void hopelessSizesFallBackToTheLink(@TempDir Path dir) {

        // Every cap is 1 MB, the "file" declares 5 MB: no native delivery is possible.
        AppConfig.Delivery caps = new AppConfig.Delivery(Map.of(
                Platform.TELEGRAM, AppConfig.Delivery.SizeCaps.uniform(
                        ByteFormat.parse("1mb", "t"))), true);

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"), caps,
                        ByteFormat.parse("1gb", "t")),
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, ByteFormat.parse("5mb", "t"))))) {

            submit(bot, "https://youtube.com/watch?v=x");

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 1).size() == 1
                            && bot.storage.jobs().recent(USER, 1).get(0).status().equals("SUCCEEDED"),
                    "job success via link fallback");

            // No media message went out; the user got ack + link-fallback + done.
            assertThat(bot.adapter.sentOfType(OutboundMessage.SendMedia.class)).isEmpty();
            assertThat(bot.adapter.sentOfType(OutboundMessage.Send.class).size())
                    .isGreaterThanOrEqualTo(2);

        }

    }

    @Test
    void blockedExtensionsAreNeverDelivered(@TempDir Path dir) {

        // The bot must not become a malware delivery channel: an .exe "download"
        // produces a refusal message, not a document.
        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(TestBot.exeExtractor()))) {

            submit(bot, "https://example.com/download");

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 1).size() == 1
                            && bot.storage.jobs().recent(USER, 1).get(0).status().equals("SUCCEEDED"),
                    "job completion");

            assertThat(bot.adapter.sentOfType(OutboundMessage.SendMedia.class)).isEmpty();
            assertThat(bot.metrics.count("deliveries_blocked_total", "backend", "exe"))
                    .isEqualTo(1);

        }

    }

    @Test
    void theSameUrlWithinTheGraceWindowIsServedFromCache(@TempDir Path dir) {

        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var last = new java.util.concurrent.atomic.AtomicReference<ExtractionRequest>();

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(TestBot.recordingExtractor(MediaKind.VIDEO, 1000, calls, last)))) {

            assertThat(submit(bot, "https://youtube.com/watch?v=cache-me"))
                    .isInstanceOf(JobManager.SubmitOutcome.Accepted.class);

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 1).get(0)
                    .status().equals("SUCCEEDED"), "first job");

            int sentAfterFirst = bot.adapter.sentOfType(OutboundMessage.SendMedia.class).size();

            assertThat(submit(bot, "https://youtube.com/watch?v=cache-me"))
                    .isInstanceOf(JobManager.SubmitOutcome.Accepted.class);

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 2).size() == 2
                            && bot.storage.jobs().recent(USER, 2).stream()
                                    .allMatch(job -> job.status().equals("SUCCEEDED")),
                    "second job");

            // The extractor ran ONCE; the second request was served from the vault.
            assertThat(calls.get()).isEqualTo(1);
            assertThat(bot.storage.jobs().recent(USER, 2).get(0).backend()).isEqualTo("cache");
            assertThat(bot.adapter.sentOfType(OutboundMessage.SendMedia.class).size())
                    .isEqualTo(sentAfterFirst + 1);
            assertThat(bot.metrics.count("downloads_total", "result", "cache_hit",
                    "source", "youtube")).isEqualTo(1);

        }

    }

    @Test
    void failedExtractionsLandInTheJobLog(@TempDir Path dir) {

        var failing = new eu.neydev.saver.core.extract.Extractor() {

            @Override
            public String backendId() {
                return "broken";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {
                throw new eu.neydev.saver.core.extract.ExtractionException(
                        eu.neydev.saver.core.extract.ExtractionException.Category.PRIVATE,
                        "broken", "private video");
            }

        };

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(failing))) {

            submit(bot, "https://youtube.com/watch?v=priv");

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 1).size() == 1
                            && bot.storage.jobs().recent(USER, 1).get(0).status().equals("FAILED"),
                    "failed job logged");

            var record = bot.storage.jobs().recent(USER, 1).get(0);

            assertThat(record.errorCode()).isEqualTo("private");
            assertThat(bot.metrics.count("downloads_total", "result", "failed",
                    "category", "private")).isEqualTo(1);

        }

    }

}
