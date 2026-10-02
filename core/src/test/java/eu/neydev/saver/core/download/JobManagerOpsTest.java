package eu.neydev.saver.core.download;

import eu.neydev.saver.core.TestBot;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.Extractor;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.storage.JobRepository;
import eu.neydev.saver.core.util.ByteFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The operational invariants of the download engine: retention actually prunes,
 * shutdown drains instead of amputating, a storage failure does not strand vault
 * reservations, an oversized video gets ONE downgrade attempt, and the cache never
 * crosses quality lines.
 */
class JobManagerOpsTest {

    private static final PlatformUser USER = new PlatformUser(Platform.TELEGRAM, "42");

    private static JobManager.SubmitOutcome submit(TestBot bot, String url) {
        return bot.jobManager.submit(USER, "chat-1", url, QualityPreset.BEST, Locale.ENGLISH);
    }

    private static JobManager.SubmitOutcome submit(TestBot bot, String url, QualityPreset q) {
        return bot.jobManager.submit(USER, "chat-1", url, q, Locale.ENGLISH);
    }

    @Test
    void housekeepingPrunesTheJobLogAndUsage(@TempDir Path dir) {

        // retention = 1 day: rows older than a day must be gone after housekeep().
        AppConfig base = TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t"));

        AppConfig config = new AppConfig(base.storage(),
                new AppConfig.Downloader(base.downloader().workers(),
                        base.downloader().queueCapacity(), base.downloader().perUserConcurrent(),
                        base.downloader().maxItemsPerRequest(), base.downloader().extractTimeout(),
                        base.downloader().downloadTimeout(), base.downloader().maxFileBytes(),
                        base.downloader().defaultQuality(), base.downloader().allowPrivateNetworks(),
                        base.downloader().proxy(), base.downloader().cookiesFile(),
                        base.downloader().cookiesOwnerOnly(), base.downloader().perSourcePerSecond(),
                        base.downloader().sleepRequests(),
                        Duration.ofDays(1),
                        base.downloader().shutdownDrain(), base.downloader().writeSubs(),
                        base.downloader().audioThumbnail(), base.downloader().blockedExtensions(),
                        base.downloader().tools(), base.downloader().vault()),
                base.limits(), base.delivery(), base.pipeline(), base.webApp(), base.locale(),
                base.sources(), base.community(), base.platforms(), base.ownerKeys(), base.status());

        try (TestBot bot = TestBot.with(dir, config,
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, 100)))) {

            Instant now = Instant.now();

            bot.storage.jobs().insertQueued("fresh", USER, "u1", now);
            bot.storage.jobs().insertQueued("ancient", USER, "u2", now.minus(Duration.ofDays(5)));
            bot.storage.usage().increment(USER, java.time.LocalDate.now().toEpochDay(), 1, 10);
            bot.storage.usage().increment(USER, java.time.LocalDate.now().minusDays(5).toEpochDay(), 1, 10);

            bot.jobManager.housekeep();

            assertThat(bot.storage.jobs().totalJobs()).isEqualTo(1);
            assertThat(bot.storage.usage().get(USER,
                    java.time.LocalDate.now().minusDays(5).toEpochDay()).jobs()).isZero();

        }

    }

    @Test
    void shutdownDrainsTheRunningDownload(@TempDir Path dir) {

        // A download that takes ~600 ms; drain is 2 s: close() must WAIT for it,
        // not amputate it - the user's file finishes and the job lands SUCCEEDED.
        Extractor sleepy = new Extractor() {

            @Override
            public String backendId() {
                return "sleepy";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {

                try {
                    Thread.sleep(600);
                    Path file = request.workDir().resolve("late.mp4");
                    Files.write(file, new byte[]{5});
                    return new ExtractionResult(request.source(), "sleepy",
                            List.of(ExtractedItem.of(MediaKind.VIDEO, file)),
                            null, null, request.url().toString(), false);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ExtractionException(Category.CANCELLED, "sleepy", "interrupted");
                }

            }

        };

        TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(sleepy));

        try {

            submit(bot, "https://youtube.com/watch?v=slow");
            TestBot.waitUntil(() -> bot.jobManager.activeCount() == 1, "job start");

            bot.jobManager.close();   // drain window = 2s from TestBot.config

            assertThat(bot.storage.jobs().recent(USER, 1).get(0).status()).isEqualTo("SUCCEEDED");

        } finally {
            bot.close();
        }

    }

    @Test
    void aStorageFailureDoesNotStrandTheVaultReservation(@TempDir Path dir) {

        TestBot.jobsOverride = delegate -> new JobRepository() {

            @Override
            public boolean insertQueuedIfUnderHourQuota(String id, PlatformUser user, String url,
                                                        Instant createdAt, Instant windowStart,
                                                        int limit) {
                throw new eu.neydev.saver.core.storage.JdbcStorage.StorageException(
                        "test", new IllegalStateException("storage down"));
            }

            @Override
            public void insertQueued(String id, PlatformUser user, String url, Instant createdAt) {
                delegate.insertQueued(id, user, url, createdAt);
            }

            @Override
            public void updateStarted(String id, String sourceId, Instant startedAt) {
                delegate.updateStarted(id, sourceId, startedAt);
            }

            @Override
            public void updateFinished(String id, String status, String errorCode, String backend,
                                       int items, long bytes, Instant finishedAt, long durationMillis) {
                delegate.updateFinished(id, status, errorCode, backend, items, bytes,
                        finishedAt, durationMillis);
            }

            @Override
            public int countSince(PlatformUser user, Instant since) {
                return delegate.countSince(user, since);
            }

            @Override
            public List<JobRecord> recent(PlatformUser user, int limit) {
                return delegate.recent(user, limit);
            }

            @Override
            public Map<String, Long> statusCounts() {
                return delegate.statusCounts();
            }

            @Override
            public List<Map.Entry<String, Long>> topSources(int limit) {
                return delegate.topSources(limit);
            }

            @Override
            public long totalJobs() {
                return delegate.totalJobs();
            }

            @Override
            public long totalBytes() {
                return delegate.totalBytes();
            }

            @Override
            public int pruneBefore(Instant cutoff) {
                return delegate.pruneBefore(cutoff);
            }

            @Override
            public int markInterrupted(Instant now) {
                return delegate.markInterrupted(now);
            }

        };

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(TestBot.fileExtractor(MediaKind.VIDEO, 100)))) {

            assertThatThrownBy(() -> submit(bot, "https://youtube.com/watch?v=x"))
                    .isInstanceOf(eu.neydev.saver.core.storage.JdbcStorage.StorageException.class);

            // The reservation taken before the INSERT must be given back - otherwise
            // phantom bytes shrink the vault quota with every storage hiccup.
            assertThat(bot.vault.bytesReserved()).isZero();

        } finally {
            TestBot.jobsOverride = null;
        }

    }

    @Test
    void tooLargeGetsOneDowngradeAttemptAt480(@TempDir Path dir) {

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<QualityPreset> lastQuality = new AtomicReference<>();

        Extractor flaky = new Extractor() {

            @Override
            public String backendId() {
                return "flaky";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {

                lastQuality.set(request.quality());

                if (calls.incrementAndGet() == 1) {
                    throw new ExtractionException(Category.TOO_LARGE, "flaky",
                            "stream exceeded the cap");
                }

                try {

                    Path file = request.workDir().resolve("small.mp4");
                    Files.write(file, new byte[]{9});

                    return new ExtractionResult(request.source(), "flaky",
                            List.of(ExtractedItem.of(MediaKind.VIDEO, file)),
                            null, null, request.url().toString(), false);

                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }

            }

        };

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(flaky))) {

            submit(bot, "https://youtube.com/watch?v=big");

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 1).size() == 1
                            && bot.storage.jobs().recent(USER, 1).get(0).status().equals("SUCCEEDED"),
                    "downgraded success");

            assertThat(calls.get()).isEqualTo(2);
            assertThat(lastQuality.get()).isEqualTo(QualityPreset.P480);
            assertThat(bot.metrics.count("retry_downgrade_total", "from", "best")).isEqualTo(1);

        }

    }

    @Test
    void theCacheNeverCrossesQualityLines(@TempDir Path dir) {

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ExtractionRequest> last = new AtomicReference<>();

        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                        TestBot.defaultDelivery(), ByteFormat.parse("1gb", "t")),
                List.of(TestBot.recordingExtractor(MediaKind.VIDEO, 1000, calls, last)))) {

            submit(bot, "https://youtube.com/watch?v=q", QualityPreset.BEST);

            TestBot.waitUntil(() -> calls.get() == 1, "first extraction");
            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 1).get(0)
                    .status().equals("SUCCEEDED"), "first success");

            // Same URL, different quality: must MISS the cache and extract again.
            submit(bot, "https://youtube.com/watch?v=q", QualityPreset.AUDIO);

            TestBot.waitUntil(() -> calls.get() == 2, "second extraction");

            assertThat(bot.storage.jobs().recent(USER, 2).stream()
                    .map(JobRepository.JobRecord::backend).toList())
                    .contains("fake");   // no "cache" entry for the audio request

            // Same URL AND quality: this one IS a cache hit.
            submit(bot, "https://youtube.com/watch?v=q", QualityPreset.BEST);

            TestBot.waitUntil(() -> bot.storage.jobs().recent(USER, 3).size() == 3
                            && bot.storage.jobs().recent(USER, 3).get(0).status().equals("SUCCEEDED"),
                    "cache hit served");

            assertThat(bot.storage.jobs().recent(USER, 3).get(0).backend()).isEqualTo("cache");
            assertThat(calls.get()).isEqualTo(2);

        }

    }

}
