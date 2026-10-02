package eu.neydev.saver.core.pipeline;

import eu.neydev.saver.core.api.PlatformUser;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user throttling of inbound events: outbound flood-control protects the platforms,
 * and this one - the core itself from command spam (expensive handlers, DB writes).
 *
 * <p>A {@code windowMillis} window for {@code limit} events; on trigger
 * the user gets one friendly message no more than once per window
 * (otherwise anti-flood would itself become a flood).
 */
public final class UserThrottle {

    public enum Verdict { PASS, THROTTLED_SILENT, THROTTLED_REPLY }

    /** The map is cleaned from stale windows once it grows past this size. */
    private static final int PURGE_THRESHOLD = 8192;

    private static final Logger log = LoggerFactory.getLogger(UserThrottle.class);

    private record Window(long windowStart, int count, long lastReplyAt) {
    }

    private final int limit;
    private final long windowMillis;
    private final Map<PlatformUser, Window> windows = new ConcurrentHashMap<>();

    public UserThrottle(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    public Verdict tryAcquire(@NotNull PlatformUser user) {

        if (windows.size() >= PURGE_THRESHOLD) {
            int removed = purge();
            if (removed > 0) {
                log.debug("Throttle window map purged: {} stale entries", removed);
            }
        }

        long now = System.currentTimeMillis();
        long windowStart = now / windowMillis;

        Window[] replied = new Window[1];
        Window window = windows.compute(user, (key, current) -> {

            if (current == null || current.windowStart() != windowStart) {
                return new Window(windowStart, 1, 0);
            }

            replied[0] = current;
            return new Window(windowStart, current.count() + 1, current.lastReplyAt());

        });

        if (window.count() <= limit) {
            return Verdict.PASS;
        }

        boolean replyCooldownOver = now - window.lastReplyAt() >= windowMillis;

        if (!replyCooldownOver) {
            return Verdict.THROTTLED_SILENT;
        }

        windows.put(user, new Window(windowStart, window.count(), now));
        return Verdict.THROTTLED_REPLY;

    }

    public int purge() {

        long windowStart = System.currentTimeMillis() / windowMillis;
        int before = windows.size();
        windows.entrySet().removeIf(entry -> entry.getValue().windowStart() != windowStart);

        return before - windows.size();

    }

}

