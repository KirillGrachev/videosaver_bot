package eu.neydev.saver.core.api;

/**
 * Receiver of normalized inbound events. The platform adapter calls
 * {@link #accept(IncomingUpdate)} from its long polling / gateway thread;
 * the implementation (the inbound pipeline) must return quickly.
 */
@FunctionalInterface
public interface UpdateSink {

    void accept(IncomingUpdate update);

}

