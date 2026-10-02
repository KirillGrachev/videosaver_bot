package eu.neydev.saver.core.security;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
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

}
