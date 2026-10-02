package eu.neydev.saver.app.di;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import eu.neydev.saver.core.api.ConnectionProbe;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.StartupFailureProbe;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.download.FileVault;
import eu.neydev.saver.core.download.JobManager;
import eu.neydev.saver.core.http.WebhookHandler;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.metrics.PlatformHealth;
import eu.neydev.saver.core.metrics.StatusHeartbeat;
import eu.neydev.saver.core.extract.backend.ToolUpdater;
import eu.neydev.saver.core.pipeline.InboundRouter;
import eu.neydev.saver.core.pipeline.OutboundDispatcher;
import eu.neydev.saver.core.security.SsrfFilterProxy;
import eu.neydev.saver.core.service.UserService;
import eu.neydev.saver.core.storage.JdbcStorage;
import eu.neydev.saver.core.webapp.HealthServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * The lifecycle orchestrator: the single point that knows the start order and the
 * stopping. Injection via the constructor - Guice binds the graph, Bootstrap only
 * manages the lifetime.
 *
 * <p>Start order is a contract, not a coincidence: the dispatcher must know its
 * adapters BEFORE the inbound router starts producing replies, and the vault must be
 * swept BEFORE the job manager accepts work (an adopted orphan dir counts against the
 * quota from the first request).
 */
@Singleton
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final AppConfig config;
    private final JdbcStorage storage;
    private final UserService userService;
    private final MetricsRegistry metrics;
    private final InboundRouter inboundRouter;
    private final OutboundDispatcher dispatcher;
    private final JobManager jobManager;
    private final FileVault vault;
    private final SsrfFilterProxy ssrfFilterProxy;
    private final ToolUpdater toolUpdater;
    private final HealthServer healthServer;
    private final PlatformHealth platformHealth;
    private final List<PlatformAdapter> adapters;
    private final Clock clock;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /** Stopping happens either from the shutdown hook or from main - whichever comes first. */
    private final java.util.concurrent.atomic.AtomicBoolean stopped =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile StatusHeartbeat heartbeat;

    @Inject
    Bootstrap(AppConfig config,
              JdbcStorage storage,
              UserService userService,
              MetricsRegistry metrics,
              InboundRouter inboundRouter,
              OutboundDispatcher dispatcher,
              JobManager jobManager,
              FileVault vault,
              SsrfFilterProxy ssrfFilterProxy,
              ToolUpdater toolUpdater,
              HealthServer healthServer,
              PlatformHealth platformHealth,
              Clock clock,
              Set<PlatformAdapter> adapterSet) {

        this.config = config;
        this.storage = storage;
        this.userService = userService;
        this.metrics = metrics;
        this.inboundRouter = inboundRouter;
        this.dispatcher = dispatcher;
        this.jobManager = jobManager;
        this.vault = vault;
        this.ssrfFilterProxy = ssrfFilterProxy;
        this.toolUpdater = toolUpdater;
        this.healthServer = healthServer;
        this.platformHealth = platformHealth;
        this.clock = clock;

        this.adapters = List.copyOf(adapterSet);

    }

    public void start() throws Exception {

        // Jobs left by a previous incarnation can never finish: close them honestly
        // before the counters and the admin screen start lying.
        storage.jobs().markInterrupted(clock.instant());

        boolean webNeeded = config.webApp().enabled()
                || adapters.stream().anyMatch(adapter -> adapter instanceof WebhookHandler);

        if (webNeeded) {

            adapters.stream()
                    .filter(adapter -> adapter instanceof WebhookHandler)
                    .forEach(adapter -> healthServer.registerWebhook((WebhookHandler) adapter));
            adapters.forEach(healthServer::registerAdapter);
            healthServer.start();

        }

        metrics.gauge("users_total", userService::count);
        platformHealth.register(metrics, adapters.stream().map(PlatformAdapter::platform).toList());

        // The SSRF filter proxy is ALREADY listening: CoreModule starts it inside the
        // provider, because the extractors bake its address into their options at graph
        // construction time - starting it here would hand them port -1.
        // (ssrfFilterProxy.start() is idempotent; nothing to do at this point.)

        toolUpdater.start();

        vault.start();
        jobManager.start();

        // The dispatcher builds a worker pool only for the platforms it knows about and
        // rejects submit() for anything else, so registration has to happen BEFORE start():
        // an unregistered platform loses every reply without a log line.
        adapters.forEach(adapter -> {
            dispatcher.register(adapter);
            log.debug("Outbound dispatcher: platform {} registered", adapter.platform().id());
        });

        inboundRouter.start();
        dispatcher.start();

        PlatformContext context = new PlatformContext(inboundRouter, platformHealth);
        List<String> failed = new ArrayList<>();
        List<PlatformException> fatal = new ArrayList<>();

        adapters.forEach(adapter -> {

            try {

                adapter.start(context);
                PlatformException startupError = adapter instanceof StartupFailureProbe probe
                        ? probe.fatalStartupError()
                        : null;

                if (startupError != null) {
                    failed.add(adapter.platform().id());
                    fatal.add(startupError);
                } else if (adapter instanceof ConnectionProbe probe && !probe.connected()) {
                    log.info("Platform {} is connecting in the background (network unavailable - retrying)",
                            adapter.platform().id());
                } else {
                    log.info("Platform active: {}", adapter.platform().id());
                }

            } catch (RuntimeException e) {
                failed.add(adapter.platform().id());
                log.error("Platform {} did not start - the bot continues without it",
                        adapter.platform().id(), e);
            }

        });

        heartbeat = new StatusHeartbeat(config.status().heartbeatInterval(), livenessFile(),
                metrics, platformHealth, adapters, userService::count, clock);
        heartbeat.start();

        // The hook performs the stop itself: the JVM halts as soon as the hooks return and
        // never waits for the main thread, so leaving stop() to main races the exit and the
        // workers, adapters and the connection pool are closed only by luck.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
            shutdownLatch.countDown();
        }, "shutdown-trigger"));

        log.info("Saver Bot started: platforms={}, backends={}, users={}",
                adapters.size() - failed.size(), jobManager.backendAvailability(), userService.count());

        if (!failed.isEmpty()) {
            log.warn("Platforms that failed to start: {}. Check tokens/network/proxy; "
                    + "the bot keeps running with the rest", String.join(", ", failed));
        }

        if (adapters.isEmpty() || failed.size() == adapters.size()) {
            log.warn("No platform connected: links sent later will still be served once "
                    + "connectivity is restored");
        }

        if (!fatal.isEmpty()) {

            fatal.forEach(error -> log.error("Platform startup error: {} - fix the configuration",
                    error.getMessage()));

            if (failed.size() == adapters.size()) {
                PlatformException first = fatal.get(0);
                throw new PlatformException(first.getMessage() + " - fix the configuration", first);
            }

            log.warn("The bot keeps running with the platforms that started: {}",
                    adapters.stream()
                            .map(PlatformAdapter::platform)
                            .map(Platform::id)
                            .filter(id -> !failed.contains(id))
                            .toList());

        }

    }

    public void awaitShutdown() {
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Idempotent: called from the shutdown hook or from main, whichever comes first. */
    public void stop() {

        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        log.info("Stopping...");

        StatusHeartbeat beat = heartbeat;
        if (beat != null) beat.close();

        // In-flight downloads get the drain window to finish; queued ones die with the
        // workers and the next startup closes them as INTERRUPTED.
        jobManager.close();
        adapters.forEach(PlatformAdapter::stop);
        inboundRouter.close();
        // Grace before the hard close: the final "done" edits of finished downloads
        // must reach the platform, or the user is left staring at "Saving...".
        dispatcher.closeGracefully(java.time.Duration.ofSeconds(10));
        healthServer.close();
        toolUpdater.close();
        ssrfFilterProxy.close();
        vault.close();
        storage.close();

        log.info("Stopped cleanly");

    }

    /** Where watchers read the last heartbeat from, or {@code null} when disabled. */
    private @Nullable Path livenessFile() {
        String path = config.status().livenessFile();
        return path != null && !path.isBlank() ? Path.of(path) : null;
    }

    public List<PlatformAdapter> adapters() {
        return adapters;
    }

}
