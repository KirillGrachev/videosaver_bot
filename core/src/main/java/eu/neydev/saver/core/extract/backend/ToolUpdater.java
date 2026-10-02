package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.util.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The tool self-updater - what makes {@code downloader.tools.update-interval} a real
 * setting instead of a decoration. yt-dlp breaks against redesigned sites within WEEKS
 * of a release, and a bot that cannot update is a bot that silently degrades into
 * "unsupported URL" answers.
 *
 * <p>Runs {@code yt-dlp -U} and {@code gallery-dl --update} on the configured interval,
 * then re-fingerprints the {@link Toolchain} so /healthz and the admin panel show the
 * versions actually on disk. Off (interval 0, the default) in containers: there the
 * image pin owns the versions and an in-container update would be lost on the next
 * deploy anyway. Errors are logged and counted, never thrown - a failed update must
 * not touch the running engine.
 */
public final class ToolUpdater implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolUpdater.class);

    private static final Duration UPDATE_TIMEOUT = Duration.ofMinutes(5);

    private final Toolchain toolchain;
    private final Duration interval;
    private final MetricsRegistry metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    private ScheduledExecutorService scheduler;

    public ToolUpdater(Toolchain toolchain, Duration interval, MetricsRegistry metrics) {
        this.toolchain = toolchain;
        this.interval = interval;
        this.metrics = metrics;
    }

    public boolean enabled() {
        return interval != null && !interval.isZero() && !interval.isNegative();
    }

    public void start() {

        if (!enabled() || !running.compareAndSet(false, true)) {

            if (!enabled()) {
                log.info("Tool auto-update disabled (downloader.tools.update-interval = 0)");
            }

            return;

        }

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {

            Thread thread = new Thread(runnable, "tool-updater");
            thread.setDaemon(true);

            return thread;

        });

        scheduler.scheduleWithFixedDelay(this::updateAll,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);

        log.info("Tool auto-update scheduled every {}", interval);

    }

    void updateAll() {

        update("yt-dlp", toolchain.ytDlp(), List.of(toolchain.ytDlp().command(), "-U"));
        update("gallery-dl", toolchain.galleryDl(),
                List.of(toolchain.galleryDl().command(), "--update"));

        toolchain.reprobe();

    }

    private void update(String id, Toolchain.ToolState state, List<String> command) {

        if (!state.present()) {

            metrics.increment("tools_updates_total", "tool", id, "result", "skipped_missing");
            return;

        }

        try {

            ProcessRunner.Result result = new ProcessRunner(UPDATE_TIMEOUT).run(command, null);

            if (result.success()) {

                metrics.increment("tools_updates_total", "tool", id, "result", "ok");
                log.info("Tool {} self-update finished", id);

            } else {

                metrics.increment("tools_updates_total", "tool", id, "result", "failed");
                log.warn("Tool {} self-update failed (exit {}): {}",
                        id, result.exitCode(), result.errorLine());

            }

        } catch (RuntimeException e) {

            metrics.increment("tools_updates_total", "tool", id, "result", "error");
            log.warn("Tool {} self-update crashed: {}", id, e.getMessage());

        }

    }

    @Override
    public void close() {

        if (!running.compareAndSet(true, false)) {
            return;
        }

        scheduler.shutdownNow();

    }

}
