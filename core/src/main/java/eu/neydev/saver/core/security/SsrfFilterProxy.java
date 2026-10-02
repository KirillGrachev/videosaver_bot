package eu.neydev.saver.core.security;

import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The missing half of the SSRF story: yt-dlp and gallery-dl are external processes that
 * follow WHATEVER URLs the page hands them (m3u8 segment lists, CDN redirects, embed
 * players) - our guard never sees those requests. Pointing the tools at this loopback
 * proxy ({@code --proxy}) puts every outbound connection they make back under
 * {@link SsrfGuard} control:
 *
 * <ul>
 *   <li>HTTP: the absolute-form request line gives the target host - checked, then the
 *       connection is relayed byte-for-byte (transparent, keep-alive safe: a reused
 *       connection can only reach the endpoint it was checked for);</li>
 *   <li>CONNECT (TLS): host:port is checked BEFORE the tunnel opens; the bytes inside
 *       are opaque and stay opaque - no MITM, no certificates, no keys.</li>
 * </ul>
 *
 * <p>Deliberately minimal: no caching, no rewriting, one check per connection. When the
 * operator sets an explicit {@code downloader.proxy}, THAT wins and this proxy is
 * bypassed - an explicit choice, logged as such at startup.
 */
public final class SsrfFilterProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SsrfFilterProxy.class);

    private static final int RELAY_BUFFER = 16 * 1024;

    private final SsrfGuard guard;
    private final MetricsRegistry metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public SsrfFilterProxy(SsrfGuard guard, MetricsRegistry metrics) {
        this.guard = guard;
        this.metrics = metrics;
    }

    /** Binds a loopback ephemeral port. */
    public void start() throws IOException {

        if (!running.compareAndSet(false, true)) {
            return;
        }

        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);

        acceptThread = Thread.ofPlatform().daemon()
                .name("ssrf-proxy-accept")
                .start(this::acceptLoop);

        log.info("SSRF filter proxy listening on {} - extraction tools route through it", url());

    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    public int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    private void acceptLoop() {

        while (running.get()) {

            try {

                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("ssrf-proxy-conn").start(() -> handle(client));

            } catch (IOException e) {

                if (running.get()) {
                    log.debug("SSRF proxy accept failed: {}", e.getMessage());
                }

                return;

            }

        }

    }

    private void handle(Socket client) {

        try (client) {

            client.setSoTimeout(30_000);

            String requestLine = readLine(client.getInputStream());

            if (requestLine == null || requestLine.isBlank()) {
                return;
            }

            String[] parts = requestLine.split(" ");

            if (parts.length < 2) {
                reject(client, "malformed request line");
                return;
            }

            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            if ("CONNECT".equals(method)) {
                handleConnect(client, target);
            } else {
                handleHttp(client, client.getInputStream(), requestLine, target);
            }

        } catch (IOException | RuntimeException e) {
            log.debug("SSRF proxy connection failed: {}", e.getMessage());
        }

    }

    /** CONNECT host:port - check the host, answer 200, then tunnel opaque bytes. */
    private void handleConnect(Socket client, String authority) throws IOException {

        int colon = authority.lastIndexOf(':');
        String host = colon > 0 ? authority.substring(0, colon) : authority;
        int port = colon > 0 ? parsePort(authority.substring(colon + 1)) : 443;

        if (!allow(host, port)) {
            reject(client, "blocked by SSRF guard: " + host);
            return;
        }

        try (Socket upstream = new Socket()) {

            upstream.connect(new InetSocketAddress(host, port), 15_000);

            OutputStream toClient = client.getOutputStream();
            toClient.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            toClient.flush();

            relay(client, upstream);

        }

    }

    /** Plain HTTP: the absolute URI in the request line carries the host. */
    private void handleHttp(Socket client, InputStream in, String requestLine, String target)
            throws IOException {

        URI uri;

        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            reject(client, "unparsable target: " + target);
            return;
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        if (host == null || !allow(host, port)) {
            reject(client, "blocked by SSRF guard: " + host);
            return;
        }

        try (Socket upstream = new Socket()) {

            upstream.connect(new InetSocketAddress(host, port), 15_000);

            // Forward the request verbatim (absolute-form is legal for proxies), then
            // tunnel the rest of the stream in both directions - keep-alive included.
            OutputStream out = upstream.getOutputStream();
            out.write((requestLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            relay(client, upstream);

        }

    }

    private boolean allow(String host, int port) {

        try {

            guard.checkHostPort(host, port);
            return true;

        } catch (SsrfGuard.SsrfException e) {

            metrics.increment("ssrf_proxy_blocked_total");
            log.debug("SSRF proxy blocked {}:{} - {}", host, port, e.getMessage());
            return false;

        } catch (RuntimeException e) {

            metrics.increment("ssrf_proxy_blocked_total");
            return false;

        }

    }

    private void reject(Socket client, String reason) {

        try {

            String body = "502 blocked: " + reason;
            client.getOutputStream().write(("HTTP/1.1 502 Bad Gateway\r\n"
                            + "Content-Length: " + body.length() + "\r\n"
                            + "Connection: close\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

        } catch (IOException ignored) {
            // the client is gone; nothing to tell
        }

    }

    /** Copies bytes both ways until either side closes; runs on the calling vthread. */
    private void relay(Socket client, Socket upstream) throws IOException {

        Thread toUpstream = Thread.ofVirtual().name("ssrf-relay-up").start(() -> {

            try {
                copy(client.getInputStream(), upstream.getOutputStream());
                upstream.shutdownOutput();
            } catch (IOException ignored) {
                // one side closed: normal end of a proxied connection
            }

        });

        try {
            copy(upstream.getInputStream(), client.getOutputStream());
            client.shutdownOutput();
        } catch (IOException ignored) {
            // ditto
        }

        try {
            toUpstream.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private static void copy(InputStream in, OutputStream out) throws IOException {

        byte[] buffer = new byte[RELAY_BUFFER];
        int read;

        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        out.flush();

    }

    private static String readLine(InputStream in) throws IOException {

        StringBuilder sb = new StringBuilder(256);
        int c;

        while ((c = in.read()) != -1) {

            if (c == '\n') {
                break;
            }

            if (c != '\r') {
                sb.append((char) c);
            }

            if (sb.length() > 16 * 1024) {
                throw new IOException("request line too long");
            }

        }

        return c == -1 && sb.length() == 0 ? null : sb.toString();

    }

    private static int parsePort(String value) {

        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65_535 ? port : 443;
        } catch (NumberFormatException e) {
            return 443;
        }

    }

    @Override
    public void close() {

        if (!running.compareAndSet(true, false)) {
            return;
        }

        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }

    }

}
