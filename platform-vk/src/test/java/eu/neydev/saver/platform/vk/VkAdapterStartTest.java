package eu.neydev.saver.platform.vk;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.UpdateSink;
import eu.neydev.saver.core.metrics.PlatformHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * start() behavior on VK problems: a token without rights (VK 15) is a fatal
 * configuration error with an actionable hint, an unreachable api.vk.com is not -
 * the adapter stays alive and retries in the background.
 */
class VkAdapterStartTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private PlatformContext context() {

        UpdateSink sink = update -> {
        };

        return new PlatformContext(sink, new PlatformHealth());

    }

    private String apiBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/method/";
    }

    /** A fake VK API answering every method with the same body. */
    private void serve(String responseBody, AtomicInteger counter) throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            counter.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        server.start();

    }

    @Test
    void tokenWithoutScopesSurfacesFatalErrorWithHint() throws Exception {

        AtomicInteger requests = new AtomicInteger();
        serve("{\"error\":{\"error_code\":15,\"error_msg\":\"Access denied: no access to call "
                + "this method. It cannot be called with current scopes.\"}}", requests);

        VkAdapter adapter = new VkAdapter("user-oauth-token", "123", apiBase());

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            adapter.start(context());
            awaitUntil(() -> adapter.fatalStartupError() != null);
        });

        PlatformException fatal = adapter.fatalStartupError();
        assertThat(fatal).isNotNull();
        assertThat(fatal.errorCode()).isEqualTo(15);
        assertThat(fatal.getMessage())
                .contains("no rights")
                .contains("community token")
                .contains("VK_GROUP_TOKEN")
                .contains("Long Poll API");

        assertThat(fatal.getCause()).isInstanceOf(PlatformException.class);
        assertThat(adapter.connected()).isFalse();
        assertThat(requests.get()).isPositive();

        adapter.stop();

    }

    @Test
    void deniedLongPollIsNotHammeredWithRetries() throws Exception {

        AtomicInteger requests = new AtomicInteger();
        serve("{\"error\":{\"error_code\":15,\"error_msg\":\"Access denied\"}}", requests);

        VkAdapter adapter = new VkAdapter("token", "123", apiBase());

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            adapter.start(context());
            awaitUntil(() -> requests.get() >= 1);
        });

        int afterFirstDenial = requests.get();
        Thread.sleep(1_500);

        assertThat(requests.get() - afterFirstDenial)
                .as("the next attempt is scheduled in minutes, not in seconds")
                .isZero();

        adapter.stop();

    }

    @Test
    void successfulLongPollServerMarksAdapterConnected() throws Exception {

        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            boolean longPollServer = exchange.getRequestURI().getPath().endsWith("getLongPollServer");
            String json = longPollServer
                    ? "{\"response\":{\"server\":\"http://127.0.0.1:"
                            + server.getAddress().getPort() + "/poll\",\"key\":\"k\",\"ts\":\"1\"}}"
                    : "{\"ts\":\"2\",\"updates\":[]}";

            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        server.createContext("/poll", exchange -> {

            byte[] body = "{\"ts\":\"2\",\"updates\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        server.start();

        VkAdapter adapter = new VkAdapter("token", "123", apiBase());
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            adapter.start(context());
            awaitUntil(adapter::connected);
        });

        assertThat(adapter.fatalStartupError()).isNull();

        adapter.stop();
        assertThat(adapter.connected()).isFalse();

    }

    @Test
    void legacyFlatMessageNewObjectIsMapped() throws Exception {

        BlockingQueue<IncomingUpdate> updates = new LinkedBlockingQueue<>();
        AtomicInteger polls = new AtomicInteger();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/method/", exchange -> {

            exchange.getRequestBody().readAllBytes();
            String json = "{\"response\":{\"server\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/poll\",\"key\":\"k\",\"ts\":\"1\"}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        server.createContext("/poll", exchange -> {

            // Groups on an API version below 5.103 deliver the message flat
            // in "object" instead of "object.message".
            String json = polls.incrementAndGet() == 1
                    ? "{\"ts\":\"2\",\"updates\":[{\"type\":\"message_new\",\"object\":"
                            + "{\"from_id\":7,\"peer_id\":2001,\"text\":\"/start\"}}]}"
                    : "{\"ts\":\"3\",\"updates\":[]}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        server.start();

        VkAdapter adapter = new VkAdapter("token", "123", apiBase());
        adapter.start(new PlatformContext(updates::add, new PlatformHealth()));

        IncomingUpdate update = updates.poll(15, TimeUnit.SECONDS);
        assertThat(update).isInstanceOf(IncomingUpdate.TextMessage.class);
        assertThat(((IncomingUpdate.TextMessage) update).text()).isEqualTo("/start");
        assertThat(update.chatId()).isEqualTo("2001");

        adapter.stop();

    }

    @Test
    void unreachableApiKeepsBotAliveAndRetries() throws Exception {

        int deadPort;

        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        VkAdapter adapter = new VkAdapter("token", "123",
                "http://127.0.0.1:" + deadPort + "/method/");

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> adapter.start(context()));

        Thread.sleep(500);

        assertThat(adapter.fatalStartupError()).isNull();
        assertThat(adapter.connected()).isFalse();

        adapter.stop();

    }

    @Test
    void permissionErrorsAreRecognizedByVkCode() {

        assertThat(VkAdapter.isPermissionDenial(new PlatformException("VK 15", null, 15))).isTrue();
        assertThat(VkAdapter.isPermissionDenial(new PlatformException("VK 5", null, 5))).isTrue();
        assertThat(VkAdapter.isPermissionDenial(new PlatformException("VK 6", null, 6))).isFalse();
        assertThat(VkAdapter.isPermissionDenial(new PlatformException("network"))).isFalse();
        assertThat(VkAdapter.isPermissionDenial(null)).isFalse();

    }

    @Test
    void hintNamesTheExactVkSettingsAndTokenKind() {
        assertThat(VkAdapter.permissionHint())
                .contains("messages")
                .contains("5.199")
                .contains("message_new")
                .contains("message_event")
                .contains("community token");
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {

        long deadline = System.currentTimeMillis() + 10_000;

        while (!condition.getAsBoolean()) {

            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 10 s");
            }

            Thread.sleep(50);

        }

    }

}

