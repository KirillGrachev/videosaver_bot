package eu.neydev.saver.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.QualityPreset;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC storage (SQLite / PostgreSQL) with a HikariCP pool and VERSIONED migrations.
 *
 * <p>The schema evolves through the {@link Migration} registry: the table
 * {@code schema_version} stores the applied version, migrations are applied in order,
 * each in its own transaction.
 *
 * <p>SQLite is tuned for load: WAL, {@code synchronous=NORMAL}, {@code busy_timeout}.
 * Time is epoch-millis (BIGINT) on both DBMSs. Upserts use
 * {@code ON CONFLICT ... DO UPDATE}, which both dialects speak - one code path,
 * no branching per database.
 */
public final class JdbcStorage implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcStorage.class);

    private final HikariDataSource dataSource;
    private final Clock clock;
    private final UserRepository users;
    private final JobRepository jobs;
    private final UsageRepository usage;
    private int schemaVersion;

    /** Any SQL failure becomes this: storage problems are operational, not catchable. */
    public static class StorageException extends RuntimeException {

        public StorageException(String what, Throwable cause) {
            super("Storage failure in " + what, cause);
        }

    }

    private final boolean postgres;

    private JdbcStorage(HikariDataSource dataSource, Clock clock, boolean postgres) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.postgres = postgres;
        this.users = new JdbcUserRepository(dataSource, clock);
        this.jobs = new JdbcJobRepository(dataSource, postgres);
        this.usage = new JdbcUsageRepository(dataSource);
    }

    public static JdbcStorage open(AppConfig.Storage config, Clock clock) {

        HikariDataSource dataSource = createDataSource(config);
        JdbcStorage storage = new JdbcStorage(dataSource, clock,
                config.type() == AppConfig.Storage.Type.POSTGRES);
        storage.migrate();

        return storage;

    }

    /** Migration registry: v1 - initial schema, then - evolution. */
    public static List<Migration> migrations() {

        return List.of(

                new Migration(1, "initial schema", List.of(
                        """
                        CREATE TABLE IF NOT EXISTS users (
                            platform    TEXT NOT NULL,
                            external_id TEXT NOT NULL,
                            chat_id     TEXT NOT NULL,
                            locale      TEXT NOT NULL,
                            quality     TEXT NOT NULL,
                            created_at  BIGINT NOT NULL,
                            updated_at  BIGINT NOT NULL,
                            PRIMARY KEY (platform, external_id)
                        )""",
                        """
                        CREATE TABLE IF NOT EXISTS jobs (
                            id           TEXT PRIMARY KEY,
                            platform     TEXT NOT NULL,
                            external_id  TEXT NOT NULL,
                            url          TEXT NOT NULL,
                            source_id    TEXT,
                            backend      TEXT,
                            status       TEXT NOT NULL,
                            error_code   TEXT,
                            items        INTEGER NOT NULL DEFAULT 0,
                            bytes        BIGINT NOT NULL DEFAULT 0,
                            created_at   BIGINT NOT NULL,
                            started_at   BIGINT,
                            finished_at  BIGINT,
                            duration_ms  BIGINT NOT NULL DEFAULT 0
                        )""",
                        // The quota counter reads jobs by (user, created_at) - index it.
                        "CREATE INDEX IF NOT EXISTS idx_jobs_user_created "
                                + "ON jobs (platform, external_id, created_at)",
                        "CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs (status)",
                        """
                        CREATE TABLE IF NOT EXISTS usage_daily (
                            platform    TEXT NOT NULL,
                            external_id TEXT NOT NULL,
                            epoch_day   BIGINT NOT NULL,
                            jobs        BIGINT NOT NULL DEFAULT 0,
                            bytes       BIGINT NOT NULL DEFAULT 0,
                            PRIMARY KEY (platform, external_id, epoch_day)
                        )"""))

        );

    }

    private static HikariDataSource createDataSource(AppConfig.Storage config) {

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("saver-db");
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setConnectionTimeout(5_000);

        if (config.type() == AppConfig.Storage.Type.SQLITE) {

            Path path = Path.of(config.sqlitePath()).toAbsolutePath();

            try {
                Files.createDirectories(path.getParent());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot create database directory: " + path.getParent(), e);
            }

            hikari.setJdbcUrl("jdbc:sqlite:" + path
                    + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000&foreign_keys=ON");
            // SQLite writes are serialized anyway; a big pool only adds lock contention.
            hikari.setMaximumPoolSize(Math.min(config.poolSize(), 8));
            hikari.setDriverClassName("org.sqlite.JDBC");

        } else {

            if (config.jdbcUrl() == null) {
                throw new IllegalStateException(
                        "storage.jdbc-url is required for storage.type=postgres");
            }

            hikari.setJdbcUrl(config.jdbcUrl());
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setDriverClassName("org.postgresql.Driver");

        }

        return new HikariDataSource(hikari);

    }

    private void migrate() {

        try (Connection connection = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_version (
                            version    INTEGER PRIMARY KEY,
                            applied_at BIGINT NOT NULL
                        )""");
            }

            int current = 0;

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {

                if (rs.next()) {
                    current = rs.getInt(1);
                }

            }

            for (Migration migration : migrations()) {

                if (migration.version() <= current) {
                    continue;
                }

                try (Statement statement = connection.createStatement()) {

                    for (String sql : migration.sql()) {
                        statement.execute(sql);
                    }

                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")) {
                        insert.setInt(1, migration.version());
                        insert.setLong(2, Instant.now().toEpochMilli());
                        insert.executeUpdate();
                    }

                }

                log.info("Schema migration v{} applied: {}",
                        migration.version(), migration.description());

            }

            connection.commit();
            schemaVersion = currentVersion();

        } catch (SQLException e) {
            throw new IllegalStateException("Schema migration failed", e);
        }

    }

    private int currentVersion() {

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {

            return rs.next() ? rs.getInt(1) : 0;

        } catch (SQLException e) {
            throw new StorageException("currentVersion()", e);
        }

    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public UserRepository users() {
        return users;
    }

    public JobRepository jobs() {
        return jobs;
    }

    public UsageRepository usage() {
        return usage;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static final class JdbcUserRepository implements UserRepository {

        private static final String COLUMNS =
                "platform, external_id, chat_id, locale, quality, created_at, updated_at";

        private final HikariDataSource dataSource;
        private final Clock clock;

        private JdbcUserRepository(HikariDataSource dataSource, Clock clock) {
            this.dataSource = dataSource;
            this.clock = clock;
        }

        @Override
        public UserSettings getOrCreate(PlatformUser user, String chatId, String localeHint,
                                        QualityPreset defaultQuality) {

            long now = clock.instant().toEpochMilli();
            String locale = localeHint == null || localeHint.isBlank() ? "en" : localeHint;

            String sql = """
                    INSERT INTO users (platform, external_id, chat_id, locale, quality,
                                       created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (platform, external_id) DO UPDATE SET
                        chat_id = excluded.chat_id,
                        updated_at = excluded.updated_at
                    """;

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(sql)) {

                upsert.setString(1, user.platform().id());
                upsert.setString(2, user.externalId());
                upsert.setString(3, chatId);
                upsert.setString(4, locale);
                upsert.setString(5, defaultQuality.id());
                upsert.setLong(6, now);
                upsert.setLong(7, now);
                upsert.executeUpdate();

            } catch (SQLException e) {
                throw new StorageException("users.upsert", e);
            }

            return find(user).orElseThrow(() ->
                    new StorageException("users.getOrCreate", new IllegalStateException(
                            "row vanished after upsert for " + user.key())));

        }

        @Override
        public Optional<UserSettings> find(PlatformUser user) {

            String sql = "SELECT " + COLUMNS + " FROM users WHERE platform = ? AND external_id = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(sql)) {

                select.setString(1, user.platform().id());
                select.setString(2, user.externalId());

                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }

            } catch (SQLException e) {
                throw new StorageException("users.find", e);
            }

        }

        @Override
        public void updateLocale(PlatformUser user, String locale) {
            update(user, "locale", locale);
        }

        @Override
        public void updateQuality(PlatformUser user, QualityPreset quality) {
            update(user, "quality", quality.id());
        }

        private void update(PlatformUser user, String column, String value) {

            // The column names come from this class's own constants, never from input;
            // values still travel as parameters.
            String sql = "UPDATE users SET " + column + " = ?, updated_at = ? "
                    + "WHERE platform = ? AND external_id = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement update = connection.prepareStatement(sql)) {

                update.setString(1, value);
                update.setLong(2, clock.instant().toEpochMilli());
                update.setString(3, user.platform().id());
                update.setString(4, user.externalId());
                update.executeUpdate();

            } catch (SQLException e) {
                throw new StorageException("users.update(" + column + ")", e);
            }

        }

        @Override
        public void touchChat(PlatformUser user, String chatId) {
            update(user, "chat_id", chatId);
        }

        @Override
        public long count() {

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM users")) {

                return rs.next() ? rs.getLong(1) : 0;

            } catch (SQLException e) {
                throw new StorageException("users.count", e);
            }

        }

        @Override
        public Instant now() {
            return clock.instant();
        }

        private static UserSettings map(ResultSet rs) throws SQLException {

            return new UserSettings(
                    new PlatformUser(Platform.fromId(rs.getString("platform")),
                            rs.getString("external_id")),
                    rs.getString("chat_id"),
                    rs.getString("locale"),
                    QualityPreset.fromIdOrDefault(rs.getString("quality"), QualityPreset.BEST),
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    Instant.ofEpochMilli(rs.getLong("updated_at")));

        }

    }

    private static final class JdbcJobRepository implements JobRepository {

        private final HikariDataSource dataSource;
        private final boolean postgres;

        private JdbcJobRepository(HikariDataSource dataSource, boolean postgres) {
            this.dataSource = dataSource;
            this.postgres = postgres;
        }

        /** URLs are capped in the log: they are personal data and a paste-bomb URL
            must not inflate the table. 500 chars keep every real link whole. */
        private static final int URL_LOG_LIMIT = 500;

        @Override
        public void insertQueued(String id, PlatformUser user, String url, Instant createdAt) {

            String sql = """
                    INSERT INTO jobs (id, platform, external_id, url, status, created_at)
                    VALUES (?, ?, ?, ?, 'QUEUED', ?)
                    ON CONFLICT (id) DO NOTHING
                    """;

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement insert = connection.prepareStatement(sql)) {

                insert.setString(1, id);
                insert.setString(2, user.platform().id());
                insert.setString(3, user.externalId());
                insert.setString(4, capUrl(url));
                insert.setLong(5, createdAt.toEpochMilli());
                insert.executeUpdate();

            } catch (SQLException e) {
                throw new StorageException("jobs.insert", e);
            }

        }

        @Override
        public boolean insertQueuedIfUnderHourQuota(String id, PlatformUser user, String url,
                                                    Instant createdAt, Instant windowStart,
                                                    int limit) {

            // One statement = no check-then-insert race on SQLite (writers serialize).
            String sql = """
                    INSERT INTO jobs (id, platform, external_id, url, status, created_at)
                    SELECT ?, ?, ?, ?, 'QUEUED', ?
                    WHERE (SELECT COUNT(*) FROM jobs
                           WHERE platform = ? AND external_id = ? AND created_at >= ?) < ?
                    """;

            // PostgreSQL at READ COMMITTED does NOT serialize the count subquery:
            // two parallel transactions can both see count < limit and both insert.
            // A per-user advisory xact lock closes exactly that window, and only there.
            String lockKey = "saver-quota:" + user.platform().id() + ":" + user.externalId();

            try (Connection connection = dataSource.getConnection()) {

                boolean restoreAutoCommit = connection.getAutoCommit();

                try {

                    if (postgres) {

                        connection.setAutoCommit(false);

                        try (PreparedStatement lock = connection.prepareStatement(
                                "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                            lock.setString(1, lockKey);
                            lock.execute();
                        }

                    }

                    boolean inserted;

                    try (PreparedStatement insert = connection.prepareStatement(sql)) {

                        insert.setString(1, id);
                        insert.setString(2, user.platform().id());
                        insert.setString(3, user.externalId());
                        insert.setString(4, capUrl(url));
                        insert.setLong(5, createdAt.toEpochMilli());
                        insert.setString(6, user.platform().id());
                        insert.setString(7, user.externalId());
                        insert.setLong(8, windowStart.toEpochMilli());
                        insert.setInt(9, limit);

                        inserted = insert.executeUpdate() == 1;

                    }

                    if (postgres) {
                        connection.commit();
                    }

                    return inserted;

                } catch (SQLException e) {

                    if (postgres) {

                        try {
                            connection.rollback();
                        } catch (SQLException ignored) {
                            // the original failure is the interesting one
                        }

                    }

                    throw e;

                } finally {

                    if (postgres) {
                        connection.setAutoCommit(restoreAutoCommit);
                    }

                }

            } catch (SQLException e) {
                throw new StorageException("jobs.insertUnderQuota", e);
            }

        }

        @Override
        public int pruneBefore(Instant cutoff) {

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM jobs WHERE created_at < ?")) {

                delete.setLong(1, cutoff.toEpochMilli());

                int removed = delete.executeUpdate();

                if (removed > 0) {
                    log.info("Job log retention: removed {} row(s) older than {}", removed, cutoff);
                }

                return removed;

            } catch (SQLException e) {
                throw new StorageException("jobs.pruneBefore", e);
            }

        }

        private static String capUrl(String url) {
            return url.length() <= URL_LOG_LIMIT ? url : url.substring(0, URL_LOG_LIMIT);
        }

        @Override
        public void updateStarted(String id, String sourceId, Instant startedAt) {

            String sql = "UPDATE jobs SET status = 'RUNNING', source_id = ?, started_at = ? "
                    + "WHERE id = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement update = connection.prepareStatement(sql)) {

                update.setString(1, sourceId);
                update.setLong(2, startedAt.toEpochMilli());
                update.setString(3, id);
                update.executeUpdate();

            } catch (SQLException e) {
                throw new StorageException("jobs.updateStarted", e);
            }

        }

        @Override
        public void updateFinished(String id, String status, @Nullable String errorCode,
                                   @Nullable String backend, int items, long bytes,
                                   Instant finishedAt, long durationMillis) {

            String sql = """
                    UPDATE jobs SET status = ?, error_code = ?, backend = ?, items = ?,
                                     bytes = ?, finished_at = ?, duration_ms = ?
                    WHERE id = ?
                    """;

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement update = connection.prepareStatement(sql)) {

                update.setString(1, status);
                update.setString(2, errorCode);
                update.setString(3, backend);
                update.setInt(4, items);
                update.setLong(5, bytes);
                update.setLong(6, finishedAt.toEpochMilli());
                update.setLong(7, durationMillis);
                update.setString(8, id);
                update.executeUpdate();

            } catch (SQLException e) {
                throw new StorageException("jobs.updateFinished", e);
            }

        }

        @Override
        public int countSince(PlatformUser user, Instant since) {

            String sql = "SELECT COUNT(*) FROM jobs WHERE platform = ? AND external_id = ? "
                    + "AND created_at >= ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(sql)) {

                select.setString(1, user.platform().id());
                select.setString(2, user.externalId());
                select.setLong(3, since.toEpochMilli());

                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }

            } catch (SQLException e) {
                throw new StorageException("jobs.countSince", e);
            }

        }

        @Override
        public List<JobRecord> recent(PlatformUser user, int limit) {

            String sql = "SELECT id, platform, external_id, url, source_id, backend, status, "
                    + "error_code, items, bytes, created_at, started_at, finished_at, duration_ms "
                    + "FROM jobs WHERE platform = ? AND external_id = ? "
                    + "ORDER BY created_at DESC LIMIT " + Math.max(1, Math.min(limit, 100));

            List<JobRecord> result = new ArrayList<>();

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(sql)) {

                select.setString(1, user.platform().id());
                select.setString(2, user.externalId());

                try (ResultSet rs = select.executeQuery()) {

                    while (rs.next()) {
                        result.add(map(rs));
                    }

                }

            } catch (SQLException e) {
                throw new StorageException("jobs.recent", e);
            }

            return result;

        }

        @Override
        public Map<String, Long> statusCounts() {

            Map<String, Long> counts = new LinkedHashMap<>();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT status, COUNT(*) FROM jobs GROUP BY status")) {

                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }

            } catch (SQLException e) {
                throw new StorageException("jobs.statusCounts", e);
            }

            return counts;

        }

        @Override
        public List<Map.Entry<String, Long>> topSources(int limit) {

            String sql = "SELECT source_id, COUNT(*) AS c FROM jobs "
                    + "WHERE source_id IS NOT NULL GROUP BY source_id ORDER BY c DESC LIMIT "
                    + Math.max(1, Math.min(limit, 50));

            List<Map.Entry<String, Long>> result = new ArrayList<>();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {

                while (rs.next()) {
                    result.add(Map.entry(rs.getString(1), rs.getLong(2)));
                }

            } catch (SQLException e) {
                throw new StorageException("jobs.topSources", e);
            }

            return result;

        }

        @Override
        public long totalJobs() {
            return scalarLong("SELECT COUNT(*) FROM jobs", "jobs.totalJobs");
        }

        @Override
        public long totalBytes() {
            return scalarLong("SELECT COALESCE(SUM(bytes), 0) FROM jobs", "jobs.totalBytes");
        }

        @Override
        public int markInterrupted(Instant now) {

            String sql = "UPDATE jobs SET status = 'INTERRUPTED', finished_at = ? "
                    + "WHERE status IN ('QUEUED', 'RUNNING')";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement update = connection.prepareStatement(sql)) {

                update.setLong(1, now.toEpochMilli());

                int closed = update.executeUpdate();

                if (closed > 0) {
                    log.warn("Closed {} job(s) left over from the previous run as INTERRUPTED",
                            closed);
                }

                return closed;

            } catch (SQLException e) {
                throw new StorageException("jobs.markInterrupted", e);
            }

        }

        private long scalarLong(String sql, String what) {

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {

                return rs.next() ? rs.getLong(1) : 0;

            } catch (SQLException e) {
                throw new StorageException(what, e);
            }

        }

        private static JobRecord map(ResultSet rs) throws SQLException {

            long started = rs.getLong("started_at");
            long finished = rs.getLong("finished_at");

            return new JobRecord(
                    rs.getString("id"),
                    new PlatformUser(Platform.fromId(rs.getString("platform")),
                            rs.getString("external_id")),
                    rs.getString("url"),
                    rs.getString("source_id"),
                    rs.getString("backend"),
                    rs.getString("status"),
                    rs.getString("error_code"),
                    rs.getInt("items"),
                    rs.getLong("bytes"),
                    Instant.ofEpochMilli(rs.getLong("created_at")),
                    rs.wasNull() || started == 0 ? null : Instant.ofEpochMilli(started),
                    finished == 0 ? null : Instant.ofEpochMilli(finished),
                    rs.getLong("duration_ms"));

        }

    }

    private static final class JdbcUsageRepository implements UsageRepository {

        private final HikariDataSource dataSource;

        private JdbcUsageRepository(HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public DayUsage get(PlatformUser user, long epochDay) {

            String sql = "SELECT jobs, bytes FROM usage_daily "
                    + "WHERE platform = ? AND external_id = ? AND epoch_day = ?";

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(sql)) {

                select.setString(1, user.platform().id());
                select.setString(2, user.externalId());
                select.setLong(3, epochDay);

                try (ResultSet rs = select.executeQuery()) {

                    if (rs.next()) {
                        return new DayUsage(rs.getLong(1), rs.getLong(2));
                    }

                }

            } catch (SQLException e) {
                throw new StorageException("usage.get", e);
            }

            return DayUsage.EMPTY;

        }

        @Override
        public int pruneBeforeDay(long epochDay) {

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM usage_daily WHERE epoch_day < ?")) {

                delete.setLong(1, epochDay);

                return delete.executeUpdate();

            } catch (SQLException e) {
                throw new StorageException("usage.pruneBeforeDay", e);
            }

        }

        @Override
        public void increment(PlatformUser user, long epochDay, long jobsDelta, long bytesDelta) {

            String sql = """
                    INSERT INTO usage_daily (platform, external_id, epoch_day, jobs, bytes)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (platform, external_id, epoch_day) DO UPDATE SET
                        jobs = usage_daily.jobs + excluded.jobs,
                        bytes = usage_daily.bytes + excluded.bytes
                    """;

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(sql)) {

                upsert.setString(1, user.platform().id());
                upsert.setString(2, user.externalId());
                upsert.setLong(3, epochDay);
                upsert.setLong(4, jobsDelta);
                upsert.setLong(5, bytesDelta);
                upsert.executeUpdate();

            } catch (SQLException e) {
                throw new StorageException("usage.increment", e);
            }

        }

    }

}
