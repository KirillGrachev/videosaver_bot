package eu.neydev.saver.core.pipeline;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Backpressure: an inbound queue overflow does not break the stream and is visible in metrics. */
class InboundRouterBackpressureTest {

    @Test
    void overflowDropsWithMetricInsteadOfBlocking() throws Exception {

        MetricsRegistry metrics = new MetricsRegistry();
        CountDownLatch gate = new CountDownLatch(1);
        InboundRouter router = new InboundRouter(2, 1, update -> {

            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        }, metrics);

        router.start();

        try {

            for (int i = 0; i < 5; i++) {
                router.accept(new IncomingUpdate.TextMessage(
                        new PlatformUser(Platform.TELEGRAM, "1"), "1", "text", null));
            }

            // 2 queued + 1 in a worker = at most 3 accepted, so at least 2 of the 5 are
            // dropped. Whether the worker has already dequeued one by this moment depends
            // on virtual-thread scheduling, hence a range and not an exact number.
            for (int i = 0; i < 100 && metrics.count("inbound_dropped_total",
                    "platform", "telegram") < 2; i++) {
                Thread.sleep(10);
            }

            assertThat(metrics.count("inbound_dropped_total", "platform", "telegram"))
                    .isBetween(2L, 3L);
            // the handler is still gated: nothing may be reported as processed
            assertThat(metrics.count("inbound_processed_total", "platform", "telegram"))
                    .isZero();

        } finally {
            gate.countDown();
            router.close();
        }

    }

}

