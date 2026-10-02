package eu.neydev.saver.core.storage;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.config.AppConfig;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PostgreSQL dialect contract on an embedded server: the same repository code must
 * run on both databases, including the quota-guarded INSERT (on PG it additionally
 * takes a per-user advisory transaction lock, because READ COMMITTED alone does not
 * serialize the count subquery).
 */
class JdbcStoragePostgresTest {

    private static EmbeddedPostgres postgres;

    private static final PlatformUser USER = new PlatformUser(Platform.TELEGRAM, "42");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stopPostgres() throws IOException {

        if (postgres != null) {
            postgres.close();
        }

    }

    private static JdbcStorage open() {

        AppConfig.Storage config = new AppConfig.Storage(AppConfig.Storage.Type.POSTGRES,
                "unused", postgres.getJdbcUrl("postgres", "postgres"), "postgres", null, 4);

        return JdbcStorage.open(config, CLOCK);

    }

    @Test
    void migrationsAndBasicFlowWorkOnPostgres() {

        try (JdbcStorage storage = open()) {

            assertThat(storage.schemaVersion())
                    .isEqualTo(JdbcStorage.migrations().get(JdbcStorage.migrations().size() - 1)
                            .version());

            UserSettings settings = storage.users()
                    .getOrCreate(USER, "chat-1", "ru",
                            eu.neydev.saver.core.extract.QualityPreset.BEST);

            assertThat(settings.locale()).isEqualTo("ru");

            storage.jobs().insertQueued("pg-1", USER, "https://x/1", CLOCK.instant());
            storage.jobs().updateStarted("pg-1", "youtube", CLOCK.instant());
            storage.jobs().updateFinished("pg-1", "SUCCEEDED", null, "ytdlp",
                    1, 999, CLOCK.instant(), 1_000);

            assertThat(storage.jobs().recent(USER, 5).get(0).status()).isEqualTo("SUCCEEDED");

        }

    }

    @Test
    void theQuotaInsertWorksUnderTheAdvisoryLock() {

        try (JdbcStorage storage = open()) {

            Instant now = CLOCK.instant();

            assertThat(storage.jobs().insertQueuedIfUnderHourQuota(
                    "pg-q1", USER, "u1", now, now.minus(Duration.ofHours(1)), 1)).isTrue();
            assertThat(storage.jobs().insertQueuedIfUnderHourQuota(
                    "pg-q2", USER, "u2", now, now.minus(Duration.ofHours(1)), 1)).isFalse();

        }

    }

    @Test
    void retentionPrunesOnPostgres() {

        try (JdbcStorage storage = open()) {

            Instant now = CLOCK.instant();

            storage.jobs().insertQueued("pg-old", USER, "u", now.minus(Duration.ofDays(200)));

            assertThat(storage.jobs().pruneBefore(now.minus(Duration.ofDays(90))))
                    .isPositive();

        }

    }

}
