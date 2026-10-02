package eu.neydev.saver.core.metrics;

import eu.neydev.saver.core.api.ConnectionProbe;
import eu.neydev.saver.core.api.PlatformAdapter;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Operational visibility that needs no open port: one INFO line with the key counters
 * every {@code interval}, plus a liveness file touched on the same beat.
 *
 * <p>Why not only {@code /metrics}: in the common deployment the bot is a child process
 * of a game server on shared hosting, where nobody can reach its HTTP endpoints and the
 * supervisor knows only "the process exists". A hung JVM (a deadlock, a starved pool) is
 * indistinguishable from a healthy one there. The heartbeat line is piped into the host
 * log by the supervisor, and the liveness file's mtime is checkable from a container
 * HEALTHCHECK or any external watcher.
 *
 * <p>The line always starts with {@link #MARKER} so a supervisor can pick it out of a
 * mixed log without parsing the whole logging format.
 */
public final class StatusHeartbeat implements AutoCloseable {

    /** Prefix of the heartbeat line - the contract with supervisors reading the log. */
    public static final String MARKER = "[status]";

    private static final Logger log = LoggerFactory.getLogger(StatusHeartbeat.class);

    private final Duration interval;
    private final @Nullable Path livenessFile;
    private final MetricsRegistry metrics;
    private final PlatformHealth health;
    private final List<PlatformAdapter> adapters;
    private final LongSupplier userCount;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean livenessWarned = new AtomicBoolean();

    private final Instant startedAt;
    private volatile Thread thread;

    public StatusHeartbeat(Duration interval,
                           @Nullable Path livenessFile,
                           MetricsRegistry metrics,
                           PlatformHealth health,
                           List<PlatformAdapter> adapters,
                           LongSupplier userCount,
                           Clock clock) {
        this.interval = interval;
        this.livenessFile = livenessFile;
        this.metrics = metrics;
        this.health = health;
        this.adapters = List.copyOf(adapters);
        this.userCount = userCount;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /** Starts the periodic beat; the first one is emitted immediately. */
    public void start() {

        if (!running.compareAndSet(false, true)) {
            return;
        }

        beat();

        if (interval.isZero() || interval.isNegative()) {
            log.info("Status heartbeat disabled (interval {})", interval);
            return;
        }

        Thread worker = new Thread(this::loop, "status-heartbeat");
        worker.setDaemon(true);
        thread = worker;
        worker.start();

    }

    private void loop() {

        while (running.get()) {

            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (running.get()) {
                beat();
            }

        }

    }

    /** One heartbeat: the log line and the liveness file. Never throws. */
    public void beat() {

        String summary = summary();

        log.info("{} {}", MARKER, summary);
        writeLiveness(summary);

    }

    /**
     * The counters that answer "is it alive and is it working": uptime, the state of each
     * platform, traffic in both directions and the storage size. Deliberately one line -
     * a supervisor keeps only the last one.
     */
    String summary() {

        Instant now = clock.instant();
        StringBuilder sb = new StringBuilder();
        sb.append("uptime=").append(compact(Duration.between(startedAt, now)));

        sb.append(" platforms=");

        if (adapters.isEmpty()) {
            sb.append("none");
        }

        for (int i = 0; i < adapters.size(); i++) {

            PlatformAdapter adapter = adapters.get(i);
            String id = adapter.platform().id();
            String state = adapter instanceof ConnectionProbe probe && !probe.connected()
                    ? "connecting"
                    : "up";

            Duration idle = health.idle(adapter.platform(), now);

            sb.append(i == 0 ? "" : ",").append(id).append(':').append(state);

            if (idle.getSeconds() < Long.MAX_VALUE / 2) {
                sb.append("(idle ").append(compact(idle)).append(')');
            }

        }

        sb.append(" users=").append(userCount.getAsLong());
        sb.append(" inbound=").append(sum("inbound_processed_total"));
        sb.append(" dropped=").append(sum("inbound_dropped_total"));
        sb.append(" throttled=").append(sum("inbound_throttled_total"));
        sb.append(" inbound_errors=").append(sum("inbound_error_total"));
        sb.append(" outbound_ok=").append(outcome("ok"));
        sb.append(" outbound_failed=").append(outcome("error") + outcome("exhausted")
                + outcome("permanent_fail"));
        sb.append(" outbound_rejected=").append(sum("outbound_rejected_total"));
        sb.append(" downloads_ok=").append(metrics.count("downloads_total", "result", "ok"));
        sb.append(" downloads_failed=").append(metrics.count("downloads_total", "result", "failed"));
        sb.append(" jobs_active=").append((long) metrics.gaugeValue("jobs_active"));
        sb.append(" jobs_queued=").append((long) metrics.gaugeValue("jobs_queued"));
        sb.append(" vault_mb=").append((long) (metrics.gaugeValue("vault_bytes") / 1_000_000));
        sb.append(" inbound_queue=").append((long) metrics.gaugeValue("inbound_queue_size"));

        return sb.toString();

    }

    private long sum(String counter) {

        long total = 0;

        for (PlatformAdapter adapter : adapters) {
            total += metrics.count(counter, "platform", adapter.platform().id());
        }

        return total;

    }

    private long outcome(String result) {

        long total = 0;

        for (PlatformAdapter adapter : adapters) {
            total += metrics.count("outbound_total",
                    "platform", adapter.platform().id(), "result", result);
        }

        return total;

    }

    /**
     * Writing is atomic (temp file + move): a watcher must never read a half-written file.
     * A failure is reported once - a read-only filesystem is a deployment fact, not a
     * reason to fill the log every interval.
     */
    private void writeLiveness(String summary) {

        if (livenessFile == null) {
            return;
        }

        try {

            Path parent = livenessFile.toAbsolutePath().getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path temp = livenessFile.resolveSibling(livenessFile.getFileName() + ".tmp");
            Files.writeString(temp, clock.instant().toEpochMilli() + " " + summary + "\n",
                    StandardCharsets.UTF_8);
            Files.move(temp, livenessFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            livenessWarned.set(false);

        } catch (IOException | RuntimeException e) {
            if (livenessWarned.compareAndSet(false, true)) {
                log.warn("Status heartbeat: cannot write the liveness file {} ({}) - "
                        + "watchers will see the process as stale", livenessFile, e.toString());
            }
        }

    }

    /** A duration as {@code 1h05m} / {@code 3m12s} / {@code 45s} - readable in one log line. */
    static String compact(Duration duration) {

        long seconds = Math.max(0, duration.getSeconds());

        if (seconds >= 3600) {
            return (seconds / 3600) + "h" + String.format("%02dm", (seconds % 3600) / 60);
        }

        if (seconds >= 60) {
            return (seconds / 60) + "m" + String.format("%02ds", seconds % 60);
        }

        return seconds + "s";

    }

    @Override
    public void close() {

        running.set(false);

        Thread worker = thread;

        if (worker != null) {
            worker.interrupt();
        }

    }

}

