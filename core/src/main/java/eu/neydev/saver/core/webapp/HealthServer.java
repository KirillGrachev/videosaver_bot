package eu.neydev.saver.core.webapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.ConnectionProbe;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.download.JobManager;
import eu.neydev.saver.core.http.WebhookHandler;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.metrics.PlatformHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * The service HTTP server: /healthz, /metrics, short-lived media links (for platforms
 * that fetch by URL) and the inbound webhooks of WhatsApp/Viber. Deliberately NOT an
 * application server: no sessions, no user data, no Mini App - an ops surface with
 * three honest endpoints and a token on /metrics.
 *
 * <p>The media link endpoint streams vault files by opaque token
 * ({@link MediaLinkServer}): the token IS the authorization, the TTL is short, and
 * the endpoint reads nothing else from the request.
 */
public final class HealthServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);

    private final AppConfig config;
    private final MetricsRegistry metrics;
    private final PlatformHealth health;
    private final MediaLinkServer mediaLinks;
    private final JobManager jobManager;
    private final LongSupplier userCount;
    private final Clock clock;
    private final List<PlatformAdapter> adapters = new CopyOnWriteArrayList<>();
    private final Map<String, WebhookHandler> webhooks = new LinkedHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Instant startedAt;
    private HttpServer server;

    public HealthServer(AppConfig config, MetricsRegistry metrics, PlatformHealth health,
                        MediaLinkServer mediaLinks, JobManager jobManager,
                        LongSupplier userCount, Clock clock) {
        this.config = config;
        this.metrics = metrics;
        this.health = health;
        this.mediaLinks = mediaLinks;
        this.jobManager = jobManager;
        this.userCount = userCount;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    public void registerAdapter(PlatformAdapter adapter) {
        adapters.add(adapter);
    }

    public void registerWebhook(WebhookHandler handler) {
        webhooks.put(handler.path(), handler);
    }

    public void start() throws IOException {

        if (!config.webApp().enabled()) {
            return;
        }

        server = HttpServer.create(new InetSocketAddress(
                config.webApp().bindHost(), config.webApp().port()), 64);

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/healthz", this::handleHealthz);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/media/", this::handleMedia);

        webhooks.forEach((path, handler) -> server.createContext(path,
                exchange -> handleWebhook(exchange, handler)));

        server.start();

        if (config.webApp().bindsPublicly() && config.webApp().metricsTokenMissing()) {

            log.warn("Binding {} (not loopback) with an empty webapp.metrics-token: "
                    + "/metrics is readable by anyone who can reach the port",
                    config.webApp().bindHost());

        }

        log.info("Health server listening on {}:{} (webhooks: {})",
                config.webApp().bindHost(), config.webApp().port(),
                webhooks.isEmpty() ? "none" : String.join(", ", webhooks.keySet()));

    }

    /** The actually bound port (0 in config means "any free port" - tests use this). */
    public int boundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    @Override
    public void close() {

        if (server != null) {
            server.stop(1);
        }

    }

    private void handleHealthz(HttpExchange exchange) throws IOException {

        if (!metricsAuthorized(exchange)) {
            respond(exchange, 403, "text/plain", "forbidden");
            return;
        }

        // Jackson instead of hand-rolled StringBuilder JSON: one escaping bug in a
        // hand-built field is an ops incident nobody can parse.
        ObjectNode root = MAPPER.createObjectNode();

        root.put("status", "ok");
        root.put("uptimeSeconds", Duration.between(startedAt, clock.instant()).toSeconds());
        root.put("users", userCount.getAsLong());
        root.put("jobsActive", jobManager.activeCount());
        root.put("jobsQueued", jobManager.queuedCount());
        root.put("engine", jobManager.engineStatus());

        ObjectNode backends = root.putObject("backends");
        jobManager.backendAvailability().forEach(backends::put);

        ObjectNode platforms = root.putObject("platforms");

        for (PlatformAdapter adapter : adapters) {

            ObjectNode node = platforms.putObject(adapter.platform().id());
            boolean up = !(adapter instanceof ConnectionProbe probe) || probe.connected();
            Duration idle = health.idle(adapter.platform(), clock.instant());

            node.put("up", up);
            node.put("idleSeconds",
                    idle.getSeconds() > Long.MAX_VALUE / 2 ? -1 : idle.getSeconds());

        }

        respond(exchange, 200, "application/json", MAPPER.writeValueAsString(root));

    }

    private void handleMetrics(HttpExchange exchange) throws IOException {

        if (!metricsAuthorized(exchange)) {
            respond(exchange, 403, "text/plain", "forbidden");
            return;
        }

        respond(exchange, 200, "text/plain; version=0.0.4", metrics.prometheusText());

    }

    /**
     * An optional token for the SERVICE endpoints (/healthz and /metrics both expose
     * operational internals - tool versions, queue sizes, platform states): the
     * Authorization: Bearer header or the ?token= query parameter, compared in constant
     * time. Without a configured token the endpoints stay open - the loopback bind is
     * the default protection; a public bind without a token logs a warning at startup.
     */
    private boolean metricsAuthorized(HttpExchange exchange) {

        String expected = config.webApp().metricsToken();

        if (expected == null || expected.isBlank()) {
            return true;
        }

        String header = exchange.getRequestHeaders().getFirst("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            return java.security.MessageDigest.isEqual(
                    header.substring(7).getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8));
        }

        String fromQuery = queryParam(exchange.getRequestURI().getQuery(), "token");

        return fromQuery != null && java.security.MessageDigest.isEqual(
                fromQuery.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));

    }

    private void handleMedia(HttpExchange exchange) throws IOException {

        String path = exchange.getRequestURI().getPath();
        String token = path.substring("/media/".length());

        if (token.isBlank() || token.contains("/")) {
            respond(exchange, 404, "text/plain", "not found");
            return;
        }

        var link = mediaLinks.resolve(token);

        if (link.isEmpty() || !Files.isRegularFile(link.get().file())) {
            respond(exchange, 404, "text/plain", "expired");
            return;
        }

        MediaLinkServer.MediaLink media = link.get();

        exchange.getResponseHeaders().set("Content-Type", media.mimeType());
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + media.fileName().replace("\"", "") + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=60");

        long size = Files.size(media.file());
        exchange.sendResponseHeaders(200, size);

        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(media.file(), out);
        }

    }

    private void handleWebhook(HttpExchange exchange, WebhookHandler handler)
            throws IOException {

        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {

            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
            }

        });

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = exchange.getRequestURI().getQuery() == null
                ? ""
                : exchange.getRequestURI().getQuery();

        WebhookHandler.WebhookResponse response = handler.handle(
                new WebhookHandler.WebhookRequest(exchange.getRequestMethod(),
                        query, headers, body));

        respond(exchange, response.status(), response.contentType(), response.body());

    }

    private static void respond(HttpExchange exchange, int status, String contentType,
                                String body) throws IOException {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }

    }

    private static String queryParam(String query, String name) {

        if (query == null || query.isBlank()) {
            return null;
        }

        for (String pair : query.split("&")) {

            int eq = pair.indexOf('=');

            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }

        }

        return null;

    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
