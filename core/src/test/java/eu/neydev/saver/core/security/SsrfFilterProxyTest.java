package eu.neydev.saver.core.security;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter proxy contract: with the guard permissive, traffic flows through it
 * unchanged; with the guard strict, even a loopback target is refused at the proxy -
 * which is exactly the protection the extraction subprocesses get when they are
 * pointed at {@code --proxy <this>}.
 *
 * <p>The CONNECT tests are the regression fence for the yt-dlp
 * {@code [SSL: TLSV1_ALERT_PROTOCOL_VERSION]} incident: the proxy used to relay the
 * client's still-unread CONNECT head into the tunnel, so the origin's TLS stack saw
 * ASCII garbage ahead of the ClientHello and killed the handshake. A raw echo origin
 * (no certificates needed - the tunnel must be opaque anyway) makes the leak visible
 * byte-for-byte.
 */
class SsrfFilterProxyTest {

    private HttpServer origin;
    private SsrfFilterProxy strictProxy;
    private SsrfFilterProxy permissiveProxy;
    private final MetricsRegistry metrics = new MetricsRegistry();

    @BeforeEach
    void startOrigin() throws IOException {

        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        origin.createContext("/", exchange -> {

            byte[] body = "origin-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        });
        origin.start();

    }

    @AfterEach
    void stop() {

        origin.stop(0);

        if (strictProxy != null) strictProxy.close();
        if (permissiveProxy != null) permissiveProxy.close();

    }

    private HttpClient clientThrough(SsrfFilterProxy proxy) {

        return HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxy.port())))
                .connectTimeout(Duration.ofSeconds(5))
                .build();

    }

    @Test
    void permissiveGuardLetsTrafficThrough() throws Exception {

        permissiveProxy = new SsrfFilterProxy(new SsrfGuard(true), metrics);
        permissiveProxy.start();

        HttpResponse<String> response = clientThrough(permissiveProxy).send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + origin.getAddress().getPort() + "/file")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("origin-bytes");

    }

    @Test
    void strictGuardRefusesPrivateTargets() throws Exception {

        strictProxy = new SsrfFilterProxy(new SsrfGuard(false), metrics);
        strictProxy.start();

        // The origin IS loopback: a strict guard must refuse it at the proxy level.
        // The refusal is an honest 502 from the proxy (or a closed connection for
        // CONNECT tunnels) - never the origin's bytes.
        HttpResponse<String> response = clientThrough(strictProxy).send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + origin.getAddress().getPort() + "/file"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("blocked");
        assertThat(metrics.count("ssrf_proxy_blocked_total")).isPositive();

    }

    @Test
    void connectTunnelStaysOpaqueAndLeaksNoRequestHead() throws Exception {

        try (ServerSocket echo = echoOrigin()) {

            permissiveProxy = new SsrfFilterProxy(new SsrfGuard(true), metrics);
            permissiveProxy.start();

            try (Socket client = new Socket("127.0.0.1", permissiveProxy.port())) {

                client.setSoTimeout(5_000);
                OutputStream out = client.getOutputStream();

                out.write(connectHead(echo.getLocalPort()));
                out.flush();

                assertThat(readResponseHead(client.getInputStream()))
                        .startsWith("HTTP/1.1 200");

                // The opening bytes of a real TLS ClientHello: record type 0x16
                // (handshake), legacy version 0x0301. If the proxy leaked the CONNECT
                // head into the tunnel, the echo - and this assertion - would see
                // "Host: ..." instead, which is how an origin ends up answering a
                // protocol_version alert to a healthy client.
                byte[] hello = {0x16, 0x03, 0x01, 0x00, 0x06, 's', 'a', 'v', 'e', 'r', '!'};
                out.write(hello);
                out.flush();

                assertThat(client.getInputStream().readNBytes(hello.length))
                        .containsExactly(hello);

            }

        }

        assertThat(metrics.count("ssrf_proxy_tunnels_total")).isEqualTo(1);

    }

    @Test
    void connectToPrivateTargetIsRefusedBeforeTunneling() throws Exception {

        strictProxy = new SsrfFilterProxy(new SsrfGuard(false), metrics);
        strictProxy.start();

        try (Socket client = new Socket("127.0.0.1", strictProxy.port())) {

            client.setSoTimeout(5_000);
            client.getOutputStream().write(connectHead(9_999));
            client.getOutputStream().flush();

            // Connection: close after the refusal - readAllBytes runs to the EOF.
            String response = new String(client.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);

            assertThat(response).startsWith("HTTP/1.1 502").contains("blocked");

        }

        assertThat(metrics.count("ssrf_proxy_blocked_total")).isPositive();
        assertThat(metrics.count("ssrf_proxy_tunnels_total")).isZero();

    }

    @Test
    void establishedTunnelOutlivesTheHeaderTimeout() throws Exception {

        try (ServerSocket echo = echoOrigin()) {

            // Tiny header timeout: the deadline may cover the request head ONLY.
            permissiveProxy = new SsrfFilterProxy(new SsrfGuard(true), metrics, 200);
            permissiveProxy.start();

            try (Socket client = new Socket("127.0.0.1", permissiveProxy.port())) {

                client.setSoTimeout(5_000);
                client.getOutputStream().write(connectHead(echo.getLocalPort()));
                client.getOutputStream().flush();

                assertThat(readResponseHead(client.getInputStream()))
                        .startsWith("HTTP/1.1 200");

                // Idle several times the header timeout. Pooled keep-alive tunnels
                // legitimately pause for minutes between downloads; a read deadline
                // left armed from the header phase would kill them mid-job.
                Thread.sleep(700);

                byte[] payload = {'p', 'i', 'n', 'g'};
                client.getOutputStream().write(payload);
                client.getOutputStream().flush();

                assertThat(client.getInputStream().readNBytes(payload.length))
                        .containsExactly(payload);

            }

        }

    }

    @Test
    void silentClientIsDroppedAtTheHeaderTimeout() throws Exception {

        permissiveProxy = new SsrfFilterProxy(new SsrfGuard(true), metrics, 200);
        permissiveProxy.start();

        try (Socket client = new Socket("127.0.0.1", permissiveProxy.port())) {

            client.setSoTimeout(5_000);

            // No request head at all: the proxy must close instead of parking the
            // connection forever - a clean EOF well inside the client's own timeout.
            assertThat(client.getInputStream().read()).isEqualTo(-1);

        }

    }

    /** A CONNECT request head shaped like the one yt-dlp's http.client sends. */
    private static byte[] connectHead(int originPort) {

        return ("CONNECT 127.0.0.1:" + originPort + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + originPort + "\r\n"
                + "User-Agent: yt-dlp/2026.08.19\r\n"
                + "Proxy-Connection: keep-alive\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

    }

    /**
     * A raw TCP origin that echoes every byte back. Stands in for an https endpoint
     * without certificates: the tunnel contract is OPAQUE bytes, and an echo proves
     * exactly what the origin would have received.
     */
    private static ServerSocket echoOrigin() throws IOException {

        ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());

        Thread.ofVirtual().name("echo-origin").start(() -> {

            try (server) {

                while (true) {

                    Socket peer = server.accept();

                    Thread.ofVirtual().name("echo-peer").start(() -> {

                        try (peer) {

                            InputStream in = peer.getInputStream();
                            OutputStream out = peer.getOutputStream();
                            byte[] buffer = new byte[4096];
                            int read;

                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                                out.flush();
                            }

                        } catch (IOException ignored) {
                            // the peer went away
                        }

                    });

                }

            } catch (IOException ignored) {
                // the test closed the server - the accept loop is done
            }

        });

        return server;

    }

    /** Reads through (and including) the first empty line - the end of a response head. */
    private static String readResponseHead(InputStream in) throws IOException {

        StringBuilder sb = new StringBuilder(128);
        int c;

        while ((c = in.read()) != -1) {

            sb.append((char) c);

            if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) {
                break;
            }

        }

        return sb.toString();

    }

}
