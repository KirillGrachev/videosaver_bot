package eu.neydev.saver.core.api;

import eu.neydev.saver.core.metrics.PlatformHealth;

/**
 * The context passed to the adapter at start: an event receiver and a health bus.
 * Adapters must call {@code health().beat(platform)} on every iteration of their
 * of the transport loop (even without user events) - by this gauge
 * the operator sees a stuck long polling/gateway/webhook.
 */
public record PlatformContext(UpdateSink sink, PlatformHealth health) {

    /** Wraps the sink: every inbound event marks the platform alive. */
    public UpdateSink instrumentedSink(Platform platform) {
        return update -> {
            health.beat(platform);
            sink.accept(update);
        };
    }

}

