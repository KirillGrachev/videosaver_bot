package eu.neydev.saver.plugin.bukkit;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * Crash-restart budget of the supervised process: at most five restarts inside a rolling
 * ten minute window, backoff from five seconds doubling to a minute. A process that lived
 * longer than five minutes is healthy - its history is forgotten and the backoff restarts
 * from the beginning, so a rare crash never spends the budget of a bad day.
 */
public final class RestartPolicy {

    public static final int MAX_RESTARTS = 5;
    public static final long WINDOW_MILLIS = 10 * 60_000L;
    public static final long HEALTHY_UPTIME_MILLIS = 5 * 60_000L;

    private static final long INITIAL_MILLIS = 5_000L;
    private static final long MAX_MILLIS = 60_000L;
    private static final int MAX_BACKOFF_STEP = 6;

    private final LongSupplier clock;
    private final ArrayDeque<Long> history = new ArrayDeque<>();
    private int backoffStep;

    public RestartPolicy() {
        this(System::currentTimeMillis);
    }

    public RestartPolicy(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * The delay before the next crash restart, or {@code -1} when the budget is spent
     * and a human has to look at the log instead of watching the process loop.
     */
    public long nextRestartMillis(long uptimeMillis) {

        if (uptimeMillis >= HEALTHY_UPTIME_MILLIS) {
            reset();
        }

        long now = clock.getAsLong();
        while (!history.isEmpty() && now - history.peekFirst() > WINDOW_MILLIS) {
            history.pollFirst();
        }

        if (history.isEmpty()) {
            backoffStep = 0;
        }

        if (history.size() >= MAX_RESTARTS) {
            return -1;
        }

        history.addLast(now);

        long delay = Math.min(INITIAL_MILLIS << backoffStep, MAX_MILLIS);
        backoffStep = Math.min(backoffStep + 1, MAX_BACKOFF_STEP);

        return delay;

    }

    public void reset() {
        history.clear();
        backoffStep = 0;
    }

}
