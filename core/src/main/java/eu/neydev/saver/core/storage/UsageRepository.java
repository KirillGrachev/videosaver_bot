package eu.neydev.saver.core.storage;

import eu.neydev.saver.core.api.PlatformUser;

/**
 * Per-user daily usage counters (jobs + bytes) for the day quotas. Kept separate from
 * the job log on purpose: the quota check runs on EVERY request and must be one indexed
 * row read, not a COUNT over history.
 */
public interface UsageRepository {

    record DayUsage(long jobs, long bytes) {

        public static final DayUsage EMPTY = new DayUsage(0, 0);

    }

    DayUsage get(PlatformUser user, long epochDay);

    void increment(PlatformUser user, long epochDay, long jobsDelta, long bytesDelta);

    /** Retention: deletes daily counters older than {@code epochDay}. @return rows deleted. */
    int pruneBeforeDay(long epochDay);

}
