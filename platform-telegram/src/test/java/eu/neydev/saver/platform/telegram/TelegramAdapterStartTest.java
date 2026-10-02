package eu.neydev.saver.platform.telegram;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.UpdateSink;
import eu.neydev.saver.core.metrics.PlatformHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramUrl;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * start() behavior on connection problems: a fatal API response (invalid
 * token) is visible immediately via {@code fatalStartupError()}, and an unreachable network -
 * not a start error: the adapter stays alive and retries registration in the background.
 */
class TelegramAdapterStartTest {

    private static final String TOKEN = "123456:TEST";

    private HttpServer server;
    private FakeSocks5Server socks;

    @AfterEach
    void stopServer() {

        if (server != null) {
            server.stop(0);
        }

        if (socks != null) {
            socks.close();
        }

    }

    /**
     * A local stand-in for api.telegram.org. The library calls deleteWebhook before the first
     * getUpdates, so the answer depends on the method: a boolean for one, a list for the other.
     */
    private int startFakeApi() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            boolean webhook = exchange.getRequestURI().getPath().endsWith("deleteWebhook");
            byte[] body = ("{\"ok\":true,\"result\":" + (webhook ? "true" : "[]") + "}")
                    .getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        server.start();
        return server.getAddress().getPort();

    }

    private PlatformContext context() {
        UpdateSink sink = update -> {};
        return new PlatformContext(sink, new PlatformHealth());
    }

    private TelegramUrl apiUrl(int port) {
        return TelegramUrl.builder()
                .schema("http")
                .host("127.0.0.1")
                .port(port)
                .build();
    }

    @Test
    void invalidTokenSurfacesFatalStartupError() throws Exception {

        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            requests.incrementAndGet();
            byte[] body = "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        server.start();

        TelegramAdapter adapter = new TelegramAdapter(TOKEN, null, "Saver",
                apiUrl(server.getAddress().getPort()));

        adapter.start(context());

        assertThat(adapter.fatalStartupError()).isNotNull();
        assertThat(requests.get()).isPositive();
        adapter.stop();

    }

    @Test
    void malformedProxySurfacesFatalErrorInsteadOfSilentRetries() {

        TelegramAdapter adapter = new TelegramAdapter(TOKEN, null, "Saver",
                null, "socks5://198.51.100.9:1080:1080");

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> adapter.start(context()));

        assertThat(adapter.fatalStartupError()).isNotNull();
        assertThat(adapter.fatalStartupError().getMessage())
                .contains("platforms.telegram.proxy")
                .contains("host");
        assertThat(adapter.connected()).isFalse();

        adapter.stop();

    }

    @Test
    void authenticatedSocks5ProxyCarriesLongPolling() throws Exception {

        int apiPort = startFakeApi();
        socks = new FakeSocks5Server(FakeSocks5Server.Mode.WORKING, "user", "pass");

        TelegramAdapter adapter = new TelegramAdapter(TOKEN, null, "Saver", apiUrl(apiPort),
                "socks5://127.0.0.1:" + socks.port() + ":user:pass");

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> adapter.start(context()));

        assertThat(adapter.fatalStartupError()).isNull();
        assertThat(adapter.connected()).isTrue();
        assertThat(socks.authSucceeded()).isTrue();
        assertThat(socks.tunnels()).isPositive();

        adapter.stop();

    }

    @Test
    void rejectedSocks5LoginIsFatalInsteadOfEndlessRetries() throws Exception {

        int apiPort = startFakeApi();
        socks = new FakeSocks5Server(FakeSocks5Server.Mode.REJECT_LOGIN, "user", "pass");

        TelegramAdapter adapter = new TelegramAdapter(TOKEN, null, "Saver", apiUrl(apiPort),
                "127.0.0.1:" + socks.port() + ":user:wrong-password");

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> adapter.start(context()));

        assertThat(adapter.fatalStartupError()).isNotNull();
        assertThat(adapter.fatalStartupError().getMessage())
                .contains("SOCKS5 proxy")
                .contains("rejected user 'user'")
                .doesNotContain("wrong-password");
        assertThat(adapter.connected()).isFalse();

        adapter.stop();

    }

    @Test
    void aProxyThatRefusesTheTargetFallsBackToTheDirectRoute() throws Exception {

        // The case from a real deployment: the proxy answers the handshake but its ruleset
        // refuses api.telegram.org, while the network itself reaches Telegram. The bot must
        // not retry into the proxy forever - the direct route carries the session.
        int apiPort = startFakeApi();
        socks = new FakeSocks5Server(FakeSocks5Server.Mode.REFUSE_TARGET, null, null);

        TelegramAdapter adapter = new TelegramAdapter(TOKEN, null, "Saver", apiUrl(apiPort),
                "socks5://127.0.0.1:" + socks.port());

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> adapter.start(context()));

        assertThat(adapter.fatalStartupError()).isNull();
        assertThat(adapter.connected()).isTrue();
        assertThat(socks.connectAttempted()).isTrue();
        assertThat(socks.tunnels()).isZero();

        adapter.stop();

    }

    @Test
    void unreachableNetworkKeepsBotAliveAndRetries() throws Exception {

        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        TelegramAdapter adapter = new TelegramAdapter(TOKEN, null, "Saver", apiUrl(deadPort));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> adapter.start(context()));

        assertThat(adapter.fatalStartupError()).isNull();
        assertThat(adapter.isEnabled()).isTrue();
        adapter.stop();

    }

    @Test
    void fatalApiErrorDetectedByResponseCode() {

        assertThat(TelegramAdapter.isFatalApiError(
                new org.telegram.telegrambots.longpolling.exceptions
                        .TelegramApiErrorResponseException(401, "Unauthorized"))).isTrue();
        assertThat(TelegramAdapter.isFatalApiError(
                new org.telegram.telegrambots.longpolling.exceptions
                        .TelegramApiErrorResponseException(404, "Not Found"))).isTrue();
        assertThat(TelegramAdapter.isFatalApiError(
                new org.telegram.telegrambots.longpolling.exceptions
                        .TelegramApiErrorResponseException(429, "Too Many Requests"))).isFalse();
        assertThat(TelegramAdapter.isFatalApiError(
                new org.telegram.telegrambots.longpolling.exceptions
                        .TelegramApiErrorResponseException(500, "Server Error"))).isFalse();
        assertThat(TelegramAdapter.isFatalApiError(
                new org.telegram.telegrambots.longpolling.exceptions
                        .TelegramApiErrorResponseException(
                                new java.net.ConnectException("Connection timed out")))).isFalse();
        assertThat(TelegramAdapter.isFatalApiError(new RuntimeException("boom"))).isFalse();

    }

    @Test
    void pollingConflictDetectedByReason() {

        assertThat(TelegramAdapter.isPollingConflict(
                "409: Conflict: terminated by other getUpdates request")).isTrue();
        assertThat(TelegramAdapter.isPollingConflict("SSL peer shut down incorrectly")).isFalse();
        assertThat(TelegramAdapter.isPollingConflict(null)).isFalse();

    }

    @Test
    void registrationWithoutASecondStartKeepsOnePoller() throws Exception {

        // The library starts the session inside registerBot: a second start() throws
        // "App is already running", which used to look like a polling conflict and made
        // the adapter spawn one more poller on every retry.
        int apiPort = startFakeApi();

        TelegramAdapter adapter = new TelegramAdapter(TOKEN, null, "Saver", apiUrl(apiPort));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () -> adapter.start(context()));

        assertThat(adapter.fatalStartupError()).isNull();
        assertThat(adapter.connected()).isTrue();

        adapter.stop();

        assertThat(adapter.connected()).isFalse();

    }

}

