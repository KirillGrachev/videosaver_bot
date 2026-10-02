package eu.neydev.saver.platform.telegram;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The SOCKS5 tunnel: a login is really sent (RFC 1929), a rejected login and a wrong protocol
 * are named precisely and stop the retries, an open proxy works without credentials, and
 * a hostname goes to the proxy unresolved.
 */
class Socks5SocketFactoryTest {

    private static final String USER = "proxyUser";
    private static final String PASSWORD = "proxyPass";

    private FakeSocks5Server proxy;
    private HttpServer target;

    @AfterEach
    void stop() {

        if (target != null) {
            target.stop(0);
        }

        if (proxy != null) {
            proxy.close();
        }

    }

    private int startTarget() throws IOException {

        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        target.createContext("/", exchange -> {

            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });

        target.start();
        return target.getAddress().getPort();

    }

    private InetSocketAddress proxyAddress() {
        return new InetSocketAddress("127.0.0.1", proxy.port());
    }

    private static OkHttpClient client(Socks5SocketFactory factory) {
        return new Socks5ClientCreator(() -> factory).get();
    }

    @Test
    void authenticatedTunnelCarriesHttpTraffic() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.WORKING, USER, PASSWORD);

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Response response = client(new Socks5SocketFactory(proxyAddress(), USER, PASSWORD))
                    .newCall(new Request.Builder().url("http://127.0.0.1:" + port + "/").build())
                    .execute()) {
                assertThat(response.code()).isEqualTo(200);
                assertThat(response.body().string()).isEqualTo("pong");
            }
        });

        assertThat(proxy.authChecked()).isTrue();
        assertThat(proxy.authSucceeded()).isTrue();
        assertThat(proxy.tunnels()).isPositive();
        assertThat(proxy.connectedTo()).isEqualTo("127.0.0.1:" + port);

    }

    @Test
    void openProxyWorksAndDoesNotAskForALogin() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.WORKING, null, null);

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Response response = client(new Socks5SocketFactory(proxyAddress(), null, null))
                    .newCall(new Request.Builder().url("http://127.0.0.1:" + port + "/").build())
                    .execute()) {
                assertThat(response.code()).isEqualTo(200);
            }
        });

        assertThat(proxy.authChecked()).isFalse();
        assertThat(proxy.tunnels()).isPositive();

    }

    @Test
    void rejectedLoginIsNamedWithoutLeakingThePassword() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.REJECT_LOGIN, USER, PASSWORD);

        Socket socket = new Socks5SocketFactory(proxyAddress(), USER, "another-secret").createSocket();

        assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000))
                .isInstanceOf(Socks5FaultException.class)
                .hasMessageContaining(Socks5FaultException.MARKER)
                .hasMessageContaining("rejected user '" + USER + "'")
                .hasMessageContaining("TELEGRAM_PROXY")
                .hasMessageNotContaining("another-secret");

        assertThat(socket.isConnected()).isFalse();
        assertThat(socket.isClosed()).isTrue();

    }

    @Test
    void proxyThatNeedsALoginSaysSoWhenNoneIsConfigured() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.WORKING, USER, PASSWORD);

        Socket socket = new Socks5SocketFactory(proxyAddress(), null, null).createSocket();
        assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000))
                .isInstanceOf(Socks5FaultException.class)
                .hasMessageContaining("requires a login")
                .hasMessageContaining("host:port:user:pass");

    }

    @Test
    void httpServerOnTheProxyPortIsReportedAsSuch() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.NOT_SOCKS, null, null);

        Socket socket = new Socks5SocketFactory(proxyAddress(), null, null).createSocket();

        assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000))
                .isInstanceOf(Socks5FaultException.class)
                .hasMessageContaining("is not a SOCKS5 server")
                .hasMessageContaining("http://host:port");

    }

    @Test
    void rulesetRefusalIsFatalAndExplainsItself() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.REFUSE_CONNECT, null, null);

        Socket socket = new Socks5SocketFactory(proxyAddress(), null, null).createSocket();

        assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000))
                .isInstanceOf(Socks5FaultException.class)
                .hasMessageContaining("not allowed by ruleset");

    }

    @Test
    void refusedTargetStaysRetryable() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.REFUSE_TARGET, null, null);

        Socket socket = new Socks5SocketFactory(proxyAddress(), null, null).createSocket();

        assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000))
                .isInstanceOf(SocketException.class)
                .isNotInstanceOf(Socks5FaultException.class)
                .hasMessageContaining("connection refused by the target");

    }

    @Test
    void hostnameIsSentToTheProxyUnresolved() throws Exception {

        int port = startTarget();
        proxy = new FakeSocks5Server(FakeSocks5Server.Mode.WORKING, null, null);

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Response response = client(new Socks5SocketFactory(proxyAddress(), null, null))
                    .newCall(new Request.Builder().url("http://localhost:" + port + "/").build())
                    .execute()) {
                assertThat(response.code()).isEqualTo(200);
            }
        });

        assertThat(proxy.connectedTo()).isEqualTo("localhost:" + port);

    }

}

