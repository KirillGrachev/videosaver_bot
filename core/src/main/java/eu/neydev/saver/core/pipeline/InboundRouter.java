package eu.neydev.saver.core.pipeline;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.UpdateSink;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Inbound pipeline: adapters hand updates to a bounded queue
 * (non-blocking!), a pool of virtual-thread workers processes them in parallel.
 *
 * <p>A traffic surge does not break long polling: queue overflow = a metric
 * {@code inbound_dropped_total} + a warning, not backpressure into the platform socket.
 */
public final class InboundRouter implements UpdateSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InboundRouter.class);

    private final BlockingQueue<IncomingUpdate> queue;
    private final Consumer<IncomingUpdate> handler;
    private final MetricsRegistry metrics;
    private final int workers;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread[] pool = new Thread[0];

    public InboundRouter(int capacity, int workers,
                         Consumer<IncomingUpdate> handler, MetricsRegistry metrics) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.workers = workers;
        this.handler = handler;
        this.metrics = metrics;
    }

    public void start() {

        if (!running.compareAndSet(false, true)) {
            return;
        }

        pool = new Thread[workers];

        for (int i = 0; i < workers; i++) {
            Thread thread = Thread.ofVirtual()
                    .name("inbound-worker-" + i)
                    .start(this::workerLoop);
            pool[i] = thread;
        }

        metrics.gauge("inbound_queue_size", queue::size);
        log.info("Inbound pipeline started: workers={}, queue={}", workers, queue.remainingCapacity());

    }

    private void workerLoop() {

        while (running.get() || !queue.isEmpty()) {

            IncomingUpdate update;

            try {
                update = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (update == null) {
                continue;
            }

            try {
                handler.accept(update);
                metrics.increment("inbound_processed_total",
                        "platform", update.user().platform().id());
            } catch (Throwable t) {
                metrics.increment("inbound_error_total",
                        "platform", update.user().platform().id());
                log.error("Failed to process update from {}", update.user().key(), t);
            }

        }

    }

    @Override
    public void accept(IncomingUpdate update) {
        if (!queue.offer(update)) {
            metrics.increment("inbound_dropped_total", "platform", update.user().platform().id());
            log.warn("Inbound queue overflow - update from {} dropped", update.user().key());
        }
    }

    @Override
    public void close() {

        if (!running.compareAndSet(true, false)) {
            return;
        }

        for (Thread thread : pool) {
            thread.interrupt();
        }

        log.info("Inbound pipeline stopped");

    }

}

