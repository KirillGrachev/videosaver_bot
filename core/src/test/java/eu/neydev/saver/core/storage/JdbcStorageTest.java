package eu.neydev.saver.core.storage;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.QualityPreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The SQLite storage contract: migrations, upserts, quota counters and the job log. */
class JdbcStorageTest {

    private static final PlatformUser USER = new PlatformUser(Platform.TELEGRAM, "42");

    private JdbcStorage storage;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void open(@TempDir Path dir) {

        AppConfig.Storage config = new AppConfig.Storage(AppConfig.Storage.Type.SQLITE,
                dir.resolve("test.db").toString(), null, null, null, 4);

        storage = JdbcStorage.open(config, clock);

    }

    @AfterEach
    void close() {
        storage.close();
    }

    @Test
    void migrationsRunOnceAndReportTheVersion() {

        assertThat(storage.schemaVersion())
                .isEqualTo(JdbcStorage.migrations().get(JdbcStorage.migrations().size() - 1).version());

    }

    @Test
    void usersAreCreatedIdempotentlyAndKeepTheirSettings() {

        UserSettings first = storage.users().getOrCreate(USER, "chat-1", "ru", QualityPreset.BEST);

        assertThat(first.locale()).isEqualTo("ru");
        assertThat(first.quality()).isEqualTo(QualityPreset.BEST);

        // A second contact must NOT overwrite the chosen locale with the hint.
        UserSettings again = storage.users().getOrCreate(USER, "chat-1", "en", QualityPreset.BEST);

        assertThat(again.locale()).isEqualTo("ru");
        assertThat(storage.users().count()).isEqualTo(1);

        storage.users().updateLocale(USER, "en");
        storage.users().updateQuality(USER, QualityPreset.AUDIO);

        UserSettings updated = storage.users().find(USER).orElseThrow();

        assertThat(updated.locale()).isEqualTo("en");
        assertThat(updated.quality()).isEqualTo(QualityPreset.AUDIO);

    }

    @Test
    void jobLifecycleIsPersistedEndToEnd() {

        Instant created = clock.instant();

        storage.jobs().insertQueued("job-1", USER, "https://youtu.be/x", created);
        storage.jobs().updateStarted("job-1", "youtube", created.plusSeconds(1));
        storage.jobs().updateFinished("job-1", "SUCCEEDED", null, "ytdlp",
                2, 123_456, created.plusSeconds(30), 29_000);

        JobRepository.JobRecord record = storage.jobs().recent(USER, 10).get(0);

        assertThat(record.status()).isEqualTo("SUCCEEDED");
        assertThat(record.sourceId()).isEqualTo("youtube");
        assertThat(record.backend()).isEqualTo("ytdlp");
        assertThat(record.items()).isEqualTo(2);
        assertThat(record.bytes()).isEqualTo(123_456);
        assertThat(record.durationMillis()).isEqualTo(29_000);

        assertThat(storage.jobs().totalJobs()).isEqualTo(1);
        assertThat(storage.jobs().totalBytes()).isEqualTo(123_456);
        assertThat(storage.jobs().statusCounts()).containsEntry("SUCCEEDED", 1L);
        assertThat(storage.jobs().topSources(5)).containsExactly(
                java.util.Map.entry("youtube", 1L));

    }

    @Test
    void countSincePowersTheHourlyQuota() {

        Instant now = clock.instant();

        storage.jobs().insertQueued("j1", USER, "u1", now.minus(Duration.ofMinutes(10)));
        storage.jobs().insertQueued("j2", USER, "u2", now.minus(Duration.ofMinutes(90)));
        storage.jobs().insertQueued("j3", new PlatformUser(Platform.VK, "7"), "u3", now);

        assertThat(storage.jobs().countSince(USER, now.minus(Duration.ofHours(1)))).isEqualTo(1);
        assertThat(storage.jobs().countSince(USER, now.minus(Duration.ofHours(3)))).isEqualTo(2);

    }

    @Test
    void dailyUsageAccumulates() {

        long day = 20_000;

        storage.usage().increment(USER, day, 1, 500);
        storage.usage().increment(USER, day, 1, 250);

        UsageRepository.DayUsage usage = storage.usage().get(USER, day);

        assertThat(usage.jobs()).isEqualTo(2);
        assertThat(usage.bytes()).isEqualTo(750);
        assertThat(storage.usage().get(USER, day + 1)).isEqualTo(UsageRepository.DayUsage.EMPTY);

    }

    @Test
    void startupClosesLeftoverJobsAsInterrupted() {

        Instant now = clock.instant();

        storage.jobs().insertQueued("stale-1", USER, "u1", now);
        storage.jobs().updateStarted("stale-1", "youtube", now);
        storage.jobs().insertQueued("stale-2", USER, "u2", now);
        storage.jobs().insertQueued("done", USER, "u3", now);
        storage.jobs().updateFinished("done", "SUCCEEDED", null, null, 1, 1, now, 10);

        int closed = storage.jobs().markInterrupted(now.plusSeconds(60));

        assertThat(closed).isEqualTo(2);
        assertThat(storage.jobs().statusCounts())
                .containsEntry("INTERRUPTED", 2L)
                .containsEntry("SUCCEEDED", 1L);

    }

    @Test
    void theHourQuotaInsertIsAtomic() {

        Instant now = clock.instant();

        // Limit 2: two inserts pass, the third must fail INSIDE the statement -
        // that is the race the old check-then-insert had.
        assertThat(storage.jobs().insertQueuedIfUnderHourQuota(
                "q1", USER, "u1", now, now.minus(Duration.ofHours(1)), 2)).isTrue();
        assertThat(storage.jobs().insertQueuedIfUnderHourQuota(
                "q2", USER, "u2", now, now.minus(Duration.ofHours(1)), 2)).isTrue();
        assertThat(storage.jobs().insertQueuedIfUnderHourQuota(
                "q3", USER, "u3", now, now.minus(Duration.ofHours(1)), 2)).isFalse();

        // Another user is unaffected.
        assertThat(storage.jobs().insertQueuedIfUnderHourQuota(
                "q4", new PlatformUser(Platform.VK, "7"), "u4", now,
                now.minus(Duration.ofHours(1)), 2)).isTrue();

        // Old jobs outside the window do not count.
        storage.jobs().insertQueued("old", USER, "u0", now.minus(Duration.ofHours(5)));

        assertThat(storage.jobs().insertQueuedIfUnderHourQuota(
                "q5", USER, "u5", now, now.minus(Duration.ofHours(1)), 3)).isTrue();

    }

    @Test
    void retentionPrunesJobsAndUsage() {

        Instant now = clock.instant();

        storage.jobs().insertQueued("keep", USER, "u1", now);
        storage.jobs().insertQueued("drop", USER, "u2", now.minus(Duration.ofDays(120)));
        storage.usage().increment(USER, 1000, 1, 10);
        storage.usage().increment(USER, 20000, 1, 10);

        assertThat(storage.jobs().pruneBefore(now.minus(Duration.ofDays(90)))).isEqualTo(1);
        assertThat(storage.jobs().totalJobs()).isEqualTo(1);
        assertThat(storage.usage().pruneBeforeDay(19000)).isEqualTo(1);
        assertThat(storage.usage().get(USER, 1000)).isEqualTo(UsageRepository.DayUsage.EMPTY);
        assertThat(storage.usage().get(USER, 20000).jobs()).isEqualTo(1);

    }

    @Test
    void loggedUrlsAreCapped() {

        Instant now = clock.instant();
        String huge = "https://example.com/" + "x".repeat(2000);

        storage.jobs().insertQueued("long", USER, huge, now);

        assertThat(storage.jobs().recent(USER, 1).get(0).url()).hasSize(500);

    }

    @Test
    void duplicateJobIdsAreIgnoredNotFatal() {

        Instant now = clock.instant();

        storage.jobs().insertQueued("dup", USER, "u1", now);
        storage.jobs().insertQueued("dup", USER, "u2", now);

        assertThat(storage.jobs().totalJobs()).isEqualTo(1);

    }

}
