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
 *   <li>CONNECT (TLS): the ENTIRE request head is consumed first (leaking it into the
 *       tunnel corrupts the origin's TLS handshake), host:port is checked BEFORE the
 *       tunnel opens, and the connection is pinned to the checked DNS answer - which
 *       is what actually closes the rebinding window. The bytes inside are opaque and
 *       stay opaque: no MITM, no certificates, no keys.</li>
 * </ul>
 *
 * <p>Deliberately minimal: no caching, no rewriting, one check per connection. When the
 * operator sets an explicit {@code downloader.proxy}, THAT wins and this proxy is
 * bypassed - an explicit choice, logged as such at startup.
 */
public final class SsrfFilterProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SsrfFilterProxy.class);

    private static final int RELAY_BUFFER = 16 * 1024;
    private static final int MAX_REQUEST_HEAD_BYTES = 64 * 1024;
    private static final int DEFAULT_HEADER_TIMEOUT_MILLIS = 30_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;

    private final SsrfGuard guard;
    private final MetricsRegistry metrics;
    private final int headerTimeoutMillis;
    private final AtomicBoolean running = new AtomicBoolean();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public SsrfFilterProxy(SsrfGuard guard, MetricsRegistry metrics) {
        this(guard, metrics, DEFAULT_HEADER_TIMEOUT_MILLIS);
    }

    /**
     * Test-visible seam: a tiny header timeout lets a test prove the deadline DISAPPEARS
     * once the tunnel stands - with the production 30s value that test would take half
     * a minute per run.
     */
    SsrfFilterProxy(SsrfGuard guard, MetricsRegistry metrics, int headerTimeoutMillis) {
        this.guard = guard;
        this.metrics = metrics;
        this.headerTimeoutMillis = headerTimeoutMillis;
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

                if (!running.get() || serverSocket.isClosed()) {
                    return;
                }

                // A transient accept failure (fd exhaustion and friends) must not kill
                // the proxy: the tools bake this address into their command lines at
                // startup, so a dead accept loop fails EVERY later job. Pause briefly
                // (a persistent error must not become a hot spin) and keep serving.
                log.debug("SSRF proxy accept failed, retrying: {}", e.getMessage());

                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }

            }

        }

    }

    private void handle(Socket client) {

        try (client) {

            client.setTcpNoDelay(true);
            // The deadline covers the REQUEST HEAD only; the relay phase that follows
            // is deliberately unlimited (see beginRelayPhase).
            client.setSoTimeout(headerTimeoutMillis);

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
                handleHttp(client, requestLine, target);
            }

        } catch (IOException | RuntimeException e) {
            log.debug("SSRF proxy connection failed: {}", e.getMessage());
        }

    }

    /** CONNECT host:port - consume the head, check the host, answer 200, tunnel opaque bytes. */
    private void handleConnect(Socket client, String authority) throws IOException {

        // The client's CONNECT head (Host:, User-Agent:, ... ending with the blank
        // line) is still sitting in the socket buffer and MUST be consumed here. Relay
        // is byte-transparent: started with the head unread, it copies those ASCII
        // bytes to the origin's TLS port AHEAD of the ClientHello, the origin answers
        // the garbage "record" with a fatal protocol_version alert, and the tool dies
        // with [SSL: TLSV1_ALERT_PROTOCOL_VERSION] on every https job.
        drainRequestHead(client.getInputStream());

        Authority target = Authority.parse(authority);

        InetAddress address = allow(target.host(), target.port());

        if (address == null) {
            reject(client, "blocked by SSRF guard: " + target.host());
            return;
        }

        try (Socket upstream = new Socket()) {

            connectPinned(upstream, address, target.port());

            OutputStream toClient = client.getOutputStream();
            toClient.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            toClient.flush();

            metrics.increment("ssrf_proxy_tunnels_total");

            beginRelayPhase(client, upstream);
            relay(client, upstream);

        }

    }

    /** Plain HTTP: the absolute URI in the request line carries the host. */
    private void handleHttp(Socket client, String requestLine, String target) throws IOException {

        URI uri;

        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            reject(client, "unparsable target: " + target);
            return;
        }

        String host = uri.getHost();

        if (host == null) {
            // Origin-form ("GET /x HTTP/1.1") at a proxy: there is no endpoint to
            // check or to connect to - refuse honestly instead of guessing.
            reject(client, "proxy requires an absolute-form target");
            return;
        }

        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        InetAddress address = allow(host, port);

        if (address == null) {
            reject(client, "blocked by SSRF guard: " + host);
            return;
        }

        try (Socket upstream = new Socket()) {

            connectPinned(upstream, address, port);

            // Forward the request line verbatim (absolute-form is legal for proxies);
            // the relay carries the rest of the head and the body byte-for-byte -
            // keep-alive included.
            OutputStream out = upstream.getOutputStream();
            out.write((requestLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            metrics.increment("ssrf_proxy_requests_total");

            beginRelayPhase(client, upstream);
            relay(client, upstream);

        }

    }

    /** Checks the target and returns the DNS answer to pin into the connection; null when blocked. */
    private @Nullable InetAddress allow(String host, int port) {

        try {

            return guard.resolveAllowed(host, port);

        } catch (SsrfGuard.SsrfException e) {

            metrics.increment("ssrf_proxy_blocked_total");
            log.debug("SSRF proxy blocked {}:{} - {}", host, port, e.getMessage());
            return null;

        } catch (RuntimeException e) {

            metrics.increment("ssrf_proxy_blocked_total");
            return null;

        }

    }

    /**
     * Connects to the address the guard has JUST cleared, not to the hostname: letting
     * the socket re-resolve would give DNS a second, unchecked vote (rebinding).
     */
    private static void connectPinned(Socket socket, InetAddress address, int port)
            throws IOException {

        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);

    }

    /**
     * The head deadline has done its job; the relay phase gets NO read timeout. Pooled
     * keep-alive connections and live-stream tunnels legitimately idle far longer than
     * any header wait, and killing those mid-job is exactly the class of mystery
     * download failure this proxy is supposed to prevent. OS-level TCP keepalive reaps
     * truly dead peers eventually; both sides closing ends the relay immediately.
     */
    private static void beginRelayPhase(Socket client, Socket upstream) throws IOException {

        client.setSoTimeout(0);
        upstream.setSoTimeout(0);
        client.setKeepAlive(true);
        upstream.setKeepAlive(true);

    }

    /** Consumes the request head up to (and including) the blank line that ends it. */
    private void drainRequestHead(InputStream in) throws IOException {

        int total = 0;
        String line;

        do {

            line = readLine(in);

            if (line == null) {
                // Client hung up mid-head; the connection is finished either way.
                return;
            }

            total += line.length() + 2;

            if (total > MAX_REQUEST_HEAD_BYTES) {
                throw new IOException("request head too large");
            }

        } while (!line.isEmpty());

    }

    /** A parsed CONNECT target; IPv6 literals arrive bracketed ([::1]:443). */
    private record Authority(String host, int port) {

        static Authority parse(String authority) {

            String value = authority.trim();

            if (value.startsWith("[")) {

                int close = value.indexOf(']');

                if (close > 0) {

                    String rest = value.substring(close + 1);
                    int port = rest.startsWith(":") ? parsePort(rest.substring(1)) : 443;
                    return new Authority(value.substring(1, close), port);

                }

            }

            int colon = value.lastIndexOf(':');

            if (colon > 0) {
                return new Authority(value.substring(0, colon),
                        parsePort(value.substring(colon + 1)));
            }

            return new Authority(value, 443);

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
