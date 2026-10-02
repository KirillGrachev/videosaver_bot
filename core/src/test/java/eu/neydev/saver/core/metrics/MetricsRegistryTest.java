package eu.neydev.saver.core.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsRegistryTest {

    @Test
    void histogramsExportCumulativeBucketsCountAndSum() {

        MetricsRegistry metrics = new MetricsRegistry();

        metrics.observe("download_seconds", 0.3, "source", "youtube");
        metrics.observe("download_seconds", 7.0, "source", "youtube");

        String text = metrics.prometheusText();

        assertThat(text)
                .contains("download_seconds_bucket{le=\"0.5\",source=\"youtube\"} 1")
                .contains("download_seconds_bucket{le=\"10.0\",source=\"youtube\"} 2")
                .contains("download_seconds_bucket{le=\"+Inf\",source=\"youtube\"} 2")
                .contains("download_seconds_count{source=\"youtube\"} 2")
                .contains("download_seconds_sum{source=\"youtube\"} 7.3");

    }

    @Test
    void bucketBoundsAreCumulative() {

        MetricsRegistry metrics = new MetricsRegistry();

        metrics.observe("x_seconds", 0.05);

        String text = metrics.prometheusText();

        // A 50 ms sample lands in EVERY bucket from 0.1 upwards (cumulative semantics).
        for (double bound : MetricsRegistry.histogramBuckets()) {
            assertThat(text).contains("x_seconds_bucket{le=\"" + bound + "\"} 1");
        }

    }

    @Test
    void countersAndGaugesStillWork() {

        MetricsRegistry metrics = new MetricsRegistry();

        metrics.increment("jobs", "result", "ok");
        metrics.increment("jobs", "result", "ok");
        metrics.gauge("queue", () -> 3.0);

        assertThat(metrics.count("jobs", "result", "ok")).isEqualTo(2);
        assertThat(metrics.gaugeValue("queue")).isEqualTo(3.0);
        assertThat(metrics.prometheusText()).contains("queue{} 3.0");

    }

}
