package eu.neydev.saver.core.storage;

import eu.neydev.saver.core.api.PlatformUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The job log: every download attempt with its outcome. Powers three features that
 * must not live in memory alone: per-user quotas across restarts, the /stats answer,
 * and the admin view ("which source fails the most this week").
 */
public interface JobRepository {

    /**
     * One row of the job log.
     *
     * @param status    QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / INTERRUPTED;
     * @param errorCode ExtractionException category id on failure, else null;
     * @param backend   which extractor produced the files, else null;
     */
    record JobRecord(@NotNull String id,
                     @NotNull PlatformUser user,
                     @NotNull String url,
                     @Nullable String sourceId,
                     @Nullable String backend,
                     @NotNull String status,
                     @Nullable String errorCode,
                     int items,
                     long bytes,
                     @NotNull Instant createdAt,
                     @Nullable Instant startedAt,
                     @Nullable Instant finishedAt,
                     long durationMillis) {
    }

    void insertQueued(String id, PlatformUser user, String url, Instant createdAt);

    /**
     * Quota-safe insert: the hourly-limit check and the INSERT are ONE statement
     * ({@code INSERT ... SELECT ... WHERE COUNT(*) < limit}), so two parallel submits
     * from the same user cannot both slip under the cap - the loser simply gets
     * {@code false} and the caller refuses with the honest message.
     *
     * @return true when the row was inserted.
     */
    boolean insertQueuedIfUnderHourQuota(String id, PlatformUser user, String url,
                                         Instant createdAt, Instant windowStart, int limit);

    /**
     * Retention: deletes job rows older than the cutoff.
     *
     * @return how many rows were deleted.
     */
    int pruneBefore(Instant cutoff);

    void updateStarted(String id, String sourceId, Instant startedAt);

    void updateFinished(String id, String status, @Nullable String errorCode,
                        @Nullable String backend, int items, long bytes,
                        Instant finishedAt, long durationMillis);

    /** Jobs created by the user since {@code since} - the hourly-quota counter. */
    int countSince(PlatformUser user, Instant since);

    List<JobRecord> recent(PlatformUser user, int limit);

    /** Status -> count over the whole log, for the admin panel. */
    Map<String, Long> statusCounts();

    /** Source id -> job count, most used first. */
    List<Map.Entry<String, Long>> topSources(int limit);

    long totalJobs();

    long totalBytes();

    /**
     * Startup hygiene: jobs left QUEUED/RUNNING by a previous incarnation can never
     * finish (their processes died with the JVM), so they are closed as INTERRUPTED
     * instead of polluting the active counters and the stats forever.
     *
     * @return how many rows were closed.
     */
    int markInterrupted(Instant now);

}
