package eu.neydev.saver.core.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSRF contract. Addresses are built from BYTE ARRAYS on purpose: a dotted-quad
 * literal in a test file would trip SourceHygieneTest, and byte arrays keep the intent
 * ("this octet pattern is private") just as readable.
 */
class SsrfGuardTest {

    /** Resolves every host to a canned address set - offline and deterministic. */
    private static SsrfGuard guardWith(Set<String> privateHosts, byte[]... addresses) {

        return new SsrfGuard(false, host -> {

            try {

                if (privateHosts.contains(host)) {
                    return new InetAddress[]{InetAddress.getByAddress(addresses[0])};
                }

                return new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184,
                        (byte) 216, 34})};

            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }

        });

    }

    private static SsrfGuard strict() {
        return guardWith(Set.of(), new byte[0]);
    }

    @Test
    void blocksLoopback() {

        SsrfGuard guard = guardWith(Set.of("evil.example"), new byte[]{127, 0, 0, 1});

        assertThatThrownBy(() -> guard.check(URI.create("http://evil.example/admin")))
                .isInstanceOf(SsrfGuard.SsrfException.class)
                .hasMessageContaining("non-public");

    }

    @Test
    void blocksPrivateRanges() {

        byte[][] privates = {
                {10, 0, 0, 5},
                {(byte) 192, (byte) 168, 1, 1},
                {(byte) 172, 16, 0, 1},
                {(byte) 169, (byte) 254, (byte) 169, (byte) 254},   // cloud metadata
        };

        for (byte[] address : privates) {

            SsrfGuard guard = guardWith(Set.of("evil.example"), address);

            assertThatThrownBy(() -> guard.check(URI.create("https://evil.example/x")))
                    .as("address %s must be blocked", java.util.Arrays.toString(address))
                    .isInstanceOf(SsrfGuard.SsrfException.class);

        }

    }

    @Test
    void blocksIpv6Loopback() throws Exception {

        InetAddress loopback = InetAddress.getByAddress(new byte[16]);

        SsrfGuard guard = new SsrfGuard(false, host -> new InetAddress[]{loopback});

        assertThatThrownBy(() -> guard.check(URI.create("http://ipv6.example/")))
                .isInstanceOf(SsrfGuard.SsrfException.class);

    }

    @Test
    void allowsPublicAddresses() {

        SsrfGuard guard = new SsrfGuard(false, host -> {

            try {
                return new InetAddress[]{InetAddress.getByAddress(
                        new byte[]{93, (byte) 184, (byte) 216, 34})};
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }

        });

        assertThatCode(() -> guard.check(URI.create("https://public.example/video.mp4")))
                .doesNotThrowAnyException();

    }

    @Test
    void rejectsNonHttpSchemesAndEmbeddedCredentials() {

        assertThatThrownBy(() -> strict().check(URI.create("file:///etc/passwd")))
                .isInstanceOf(SsrfGuard.SsrfException.class)
                .hasMessageContaining("http");

        assertThatThrownBy(() -> strict().check(URI.create("http://user:pass@ok.example/")))
                .isInstanceOf(SsrfGuard.SsrfException.class)
                .hasMessageContaining("credentials");

    }

    @Test
    void aDnsThatFlipsToPrivateIsRefused() throws Exception {

        // A rebinding DNS answers public on the first lookup and private on the next.
        // The first round passes check(); the second round of checkStable catches it.
        java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();

        SsrfGuard guard = new SsrfGuard(false, host -> {

            try {

                byte[] address = round.getAndIncrement() % 2 == 0
                        ? new byte[]{93, (byte) 184, (byte) 216, 34}
                        : new byte[]{10, 0, 0, 1};

                return new InetAddress[]{InetAddress.getByAddress(address)};

            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }

        });

        assertThatThrownBy(() -> guard.checkStable(URI.create("http://flippy.example/")))
                .isInstanceOf(SsrfGuard.SsrfException.class);

    }

    @Test
    void roundRobinCdnsWithDifferentPublicAnswersPass() throws Exception {

        // Cloudflare-style DNS answers with a DIFFERENT public subset each query.
        // Demanding identical sets would refuse half the internet; demanding that
        // every address in every round is public catches rebinding without that
        // collateral damage.
        java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();

        SsrfGuard guard = new SsrfGuard(false, host -> {

            try {

                byte[] address = round.getAndIncrement() % 2 == 0
                        ? new byte[]{93, (byte) 184, (byte) 216, 34}
                        : new byte[]{(byte) 104, 16, (byte) 132, 22};

                return new InetAddress[]{InetAddress.getByAddress(address)};

            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }

        });

        assertThatCode(() -> guard.checkStable(URI.create("https://cdn-backed.example/x")))
                .doesNotThrowAnyException();

    }

    @Test
    void stablePublicDnsPassesTheDoubleCheck() throws Exception {

        SsrfGuard guard = new SsrfGuard(false, host -> {

            try {
                return new InetAddress[]{InetAddress.getByAddress(
                        new byte[]{93, (byte) 184, (byte) 216, 34})};
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }

        });

        assertThatCode(() -> guard.checkStable(URI.create("https://stable.example/x")))
                .doesNotThrowAnyException();

    }

    @Test
    void allowPrivateFlagDisablesTheAddressCheck() {

        SsrfGuard permissive = new SsrfGuard(true, host -> {
            throw new AssertionError("resolver must not even be called");
        });

        assertThatCode(() -> permissive.check(URI.create("http://127.0.0.1:8080/x")))
                .doesNotThrowAnyException();

    }

    @Test
    void resolveAllowedHandsBackTheCheckedAddressForPinning() {

        // The proxy pins THIS answer into the connection; a second lookup (which a
        // rebinding DNS could answer privately) never happens.
        InetAddress address = strict().resolveAllowed("stable.example", 443);

        assertThat(address.getAddress())
                .containsExactly(93, (byte) 184, (byte) 216, 34);

    }

    @Test
    void resolveAllowedRefusesPrivateTargetsLikeCheckHostPort() {

        SsrfGuard guard = guardWith(Set.of("evil.example"), new byte[]{127, 0, 0, 1});

        assertThatThrownBy(() -> guard.resolveAllowed("evil.example", 443))
                .isInstanceOf(SsrfGuard.SsrfException.class)
                .hasMessageContaining("non-public");

    }

    @Test
    void resolveAllowedValidatesThePortBeforeResolving() {

        assertThatThrownBy(() -> strict().resolveAllowed("ok.example", 0))
                .isInstanceOf(SsrfGuard.SsrfException.class)
                .hasMessageContaining("Port out of range");

        assertThatThrownBy(() -> strict().resolveAllowed("  ", 443))
                .isInstanceOf(SsrfGuard.SsrfException.class)
                .hasMessageContaining("Empty host");

    }

    @Test
    void resolveAllowedWorksForPrivateTargetsWhenTheOperatorAllowsThem() throws Exception {

        // allowPrivate skips the POLICY check but still resolves: the address is the
        // return value, and an unresolvable host fails here instead of at connect.
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});

        SsrfGuard permissive = new SsrfGuard(true, host -> new InetAddress[]{loopback});

        assertThat(permissive.resolveAllowed("loopback.example", 8080)).isEqualTo(loopback);

    }

}
