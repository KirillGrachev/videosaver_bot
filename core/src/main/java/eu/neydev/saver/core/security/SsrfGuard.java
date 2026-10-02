package eu.neydev.saver.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.function.Function;

/**
 * A downloader bot is an SSRF machine by design: users hand it URLs, it fetches them
 * from inside the operator's network. Without a guard, one message with
 * {@code http://169.254.169.254/latest/meta-data/} turns the bot into a proxy into the
 * cloud metadata service, and {@code http://192.168.1.1/admin} probes the LAN.
 *
 * <p>Every URL the GENERIC/DIRECT extractors touch passes {@link #check(URI)} first:
 * scheme must be http(s), credentials in the userinfo part are rejected outright, and
 * the host must not resolve to a loopback/private/link-local/multicast address. The
 * generic HTTP extractor also disables auto-redirects and re-checks EVERY hop, because
 * a public URL that 302s into the LAN defeats a check that only saw the first hop.
 *
 * <p>Known residual risk, documented not hidden: DNS can answer differently between
 * the check and the actual connect (rebinding). Pinning the resolved address into the
 * connection is the full fix; until then the guard keeps the honest common cases out
 * and self-hosted operators who NEED private targets can flip
 * {@code downloader.allow-private-networks}.
 */
public final class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    /** Rejected target: the caller converts it into an honest user-facing refusal. */
    public static class SsrfException extends RuntimeException {

        public SsrfException(String message) {
            super(message);
        }

    }

    /** DNS indirection point so tests run offline and deterministic. */
    public interface Resolver extends Function<String, InetAddress[]> {
    }

    private static final Resolver SYSTEM_RESOLVER = host -> {

        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfException("Host does not resolve: " + host);
        }

    };

    private final Resolver resolver;
    private final boolean allowPrivate;

    public SsrfGuard(boolean allowPrivate) {
        this(allowPrivate, SYSTEM_RESOLVER);
    }

    public SsrfGuard(boolean allowPrivate, Resolver resolver) {
        this.allowPrivate = allowPrivate;
        this.resolver = resolver;
    }

    /**
     * Host/port form for callers that already split the authority (the filter proxy's
     * CONNECT handling). Same rules as {@link #check(URI)}: http-target semantics,
     * every resolved address must be public.
     */
    public void checkHostPort(String host, int port) {

        if (host == null || host.isBlank()) {
            throw new SsrfException("Empty host");
        }

        if (port < 1 || port > 65_535) {
            throw new SsrfException("Port out of range: " + port);
        }

        if (allowPrivate) {
            return;
        }

        for (InetAddress address : resolver.apply(host)) {

            if (!isPublic(address)) {
                throw new SsrfException("Host resolves to a non-public address: " + host);
            }

        }

    }

    public void check(URI uri) {

        String scheme = uri.getScheme();

        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SsrfException("Only http(s) URLs are allowed: " + uri);
        }

        if (uri.getUserInfo() != null) {
            throw new SsrfException("URLs with embedded credentials are not accepted");
        }

        String host = uri.getHost();

        if (host == null || host.isBlank()) {
            throw new SsrfException("URL without a host: " + uri);
        }

        if (allowPrivate) {
            return;
        }

        for (InetAddress address : resolver.apply(host)) {

            if (!isPublic(address)) {

                log.debug("SSRF guard blocked {} -> {}", host, address.getHostAddress());
                throw new SsrfException("Host resolves to a non-public address: " + host);

            }

        }

    }

    /**
     * Rebinding-hardened check: resolves the host TWICE and requires EVERY address in
     * BOTH rounds to be public. Identity of the two answer sets is deliberately NOT
     * required - round-robin DNS (Cloudflare, Akamai, every big CDN) legitimately
     * answers with a different public subset on each query, and demanding equality
     * would refuse half the internet.
     *
     * <p>Not a full fix - only pinning the resolved address into the actual connection
     * closes rebinding completely - but a flip to a private address at ANY lookup is
     * caught here. Entry points use this; redirect hops use {@link #check(URI)}
     * (each hop is already a fresh resolve).
     */
    public void checkStable(URI uri) {

        check(uri);

        if (allowPrivate) {
            return;
        }

        String host = uri.getHost();

        for (InetAddress address : resolver.apply(host)) {

            if (!isPublic(address)) {

                log.warn("SSRF guard: second lookup of {} resolved to a non-public address - "
                        + "possible DNS rebinding", host);
                throw new SsrfException(
                        "DNS answer for " + host + " flipped to a non-public address");

            }

        }

    }

    private static boolean isPublic(InetAddress address) {

        return !address.isLoopbackAddress()
                && !address.isAnyLocalAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress()
                // IPv4-mapped IPv6 (::ffff:127.0.0.1) sneaks past the checks above on
                // some stacks; unwrap and re-test the embedded IPv4.
                && !(address instanceof java.net.Inet6Address
                        && isPublicMappedV4(address.getAddress()));

    }

    private static boolean isPublicMappedV4(byte[] bytes) {

        if (bytes.length != 16) {
            return true;
        }

        int first = bytes[12] & 0xFF;
        int second = bytes[13] & 0xFF;

        boolean privateV4 = first == 127 || first == 10 || first == 0
                || (first == 192 && second == 168)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 169 && second == 254);

        return !privateV4;

    }

}
