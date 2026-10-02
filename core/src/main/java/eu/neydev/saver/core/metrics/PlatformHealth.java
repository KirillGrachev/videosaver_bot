package eu.neydev.saver.core.metrics;

import eu.neydev.saver.core.api.Platform;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform health bus: the last moment of transport activity for each platform.
 * {@link #register(MetricsRegistry, Iterable)} exposes a gauge
 * {@code platform_idle_seconds{platform}} - the basis of "platform is silent" alerts.
 */
public final class PlatformHealth {

    private final Map<Platform, Instant> lastBeat = new ConcurrentHashMap<>();

    public void beat(@NotNull Platform platform) {
        lastBeat.put(platform, Instant.now());
    }

    public Duration idle(@NotNull Platform platform, @NotNull Instant now) {
        Instant last = lastBeat.get(platform);
        return last == null ? Duration.ofSeconds(Long.MAX_VALUE) : Duration.between(last, now);
    }

    public void register(MetricsRegistry metrics, Iterable<Platform> platforms) {
        for (Platform platform : platforms) {
            beat(platform);
            metrics.gauge("platform_idle_seconds",
                    () -> idle(platform, Instant.now()).toSeconds(),
                    "platform", platform.id());
        }
    }

}

