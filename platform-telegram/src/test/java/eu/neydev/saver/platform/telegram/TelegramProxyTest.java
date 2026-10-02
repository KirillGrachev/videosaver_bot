package eu.neydev.saver.platform.telegram;

import eu.neydev.saver.core.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.net.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing platforms.telegram.proxy: schemes, authorization, format errors. */
class TelegramProxyTest {

    @Test
    void blankMeansDirectConnection() {

        assertThat(TelegramAdapter.parseProxy(null)).isNull();
        assertThat(TelegramAdapter.parseProxy("")).isNull();
        assertThat(TelegramAdapter.parseProxy("   ")).isNull();

    }

    @Test
    void parsesHttpAndSocksForms() {

        TelegramAdapter.ProxySpec http = TelegramAdapter.parseProxy("http://203.0.113.9:8080");
        assertThat(http).isNotNull();
        assertThat(http.proxy().type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(http.proxy().address().toString()).contains("203.0.113.9").contains("8080");
        assertThat(http.credentials()).isNull();
        assertThat(http.label()).isEqualTo("http://203.0.113.9:8080");

        TelegramAdapter.ProxySpec bare = TelegramAdapter.parseProxy("203.0.113.9:3128");
        assertThat(bare).isNotNull();
        assertThat(bare.proxy().type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(bare.label()).isEqualTo("http://203.0.113.9:3128");

        TelegramAdapter.ProxySpec socks = TelegramAdapter.parseProxy("socks5://localhost:1080");
        assertThat(socks).isNotNull();
        assertThat(socks.proxy().type()).isEqualTo(Proxy.Type.SOCKS);
        assertThat(socks.label()).isEqualTo("socks5://localhost:1080");

        TelegramAdapter.ProxySpec ipv6 = TelegramAdapter.parseProxy("socks5://[::1]:1080");
        assertThat(ipv6).isNotNull();
        assertThat(ipv6.proxy().type()).isEqualTo(Proxy.Type.SOCKS);

    }

    @Test
    void parsesCredentials() {

        TelegramAdapter.ProxySpec spec = TelegramAdapter.parseProxy("http://user:pass@proxy.local:8080");
        assertThat(spec).isNotNull();
        assertThat(spec.credentials()).isNotNull();
        assertThat(spec.proxy().address().toString()).contains("proxy.local");
        assertThat(spec.label()).isEqualTo("http://proxy.local:8080");

    }

    @Test
    void rejectsMalformedSpecs() {

        assertThatThrownBy(() -> TelegramAdapter.parseProxy("proxy-without-port"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("proxy");
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("http://host:port"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("http://host:99999"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("range");

    }

    @Test
    void parsesTheSellerColonForm() {

        // Proxy sellers print their lists as host:port:user:pass - the SOCKS5 login (RFC 1929).
        TelegramAdapter.ProxySpec spec = TelegramAdapter.parseProxy(
                "socks5://203.0.113.7:1080:proxyUser:proxyPass");

        assertThat(spec).isNotNull();
        assertThat(spec.proxy().type()).isEqualTo(Proxy.Type.SOCKS);
        assertThat(spec.proxy().address().toString()).contains("203.0.113.7").contains("1080");
        assertThat(spec.username()).isEqualTo("proxyUser");
        assertThat(spec.password()).isEqualTo("proxyPass");
        assertThat(spec.schemeGiven()).isTrue();
        assertThat(spec.label()).isEqualTo("socks5://203.0.113.7:1080");

    }

    @Test
    void colonFormWithoutSchemeIsSocks5() {

        TelegramAdapter.ProxySpec spec = TelegramAdapter.parseProxy(
                "203.0.113.7:1080:proxyUser:proxyPass");

        assertThat(spec).isNotNull();
        assertThat(spec.proxy().type()).isEqualTo(Proxy.Type.SOCKS);
        assertThat(spec.username()).isEqualTo("proxyUser");
        assertThat(spec.schemeGiven()).isFalse();
        assertThat(spec.label()).isEqualTo("socks5://203.0.113.7:1080");

    }

    @Test
    void colonFormAcceptsAnHttpSchemeToo() {

        TelegramAdapter.ProxySpec spec = TelegramAdapter.parseProxy("http://203.0.113.9:8080:user:pass");

        assertThat(spec).isNotNull();
        assertThat(spec.proxy().type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(spec.username()).isEqualTo("user");
        assertThat(spec.credentials()).isNotNull();
        assertThat(spec.label()).isEqualTo("http://203.0.113.9:8080");

    }

    @Test
    void socksLoginWorksInTheUrlFormAsWell() {

        TelegramAdapter.ProxySpec spec = TelegramAdapter.parseProxy("socks5://user:pass@203.0.113.9:1080");

        assertThat(spec).isNotNull();
        assertThat(spec.proxy().type()).isEqualTo(Proxy.Type.SOCKS);
        assertThat(spec.username()).isEqualTo("user");
        assertThat(spec.password()).isEqualTo("pass");

    }

    @Test
    void parsesIpv6WithLogin() {

        TelegramAdapter.ProxySpec spec = TelegramAdapter.parseProxy("socks5://[2001:db8::1]:1080:user:pass");

        assertThat(spec).isNotNull();
        assertThat(spec.proxy().type()).isEqualTo(Proxy.Type.SOCKS);
        assertThat(spec.proxy().address().toString()).contains("2001:db8").contains("1080");
        assertThat(spec.username()).isEqualTo("user");
        assertThat(spec.password()).isEqualTo("pass");

    }

    @Test
    void passwordNeverReachesLogs() {

        TelegramAdapter.ProxySpec spec = TelegramAdapter.parseProxy(
                "socks5://203.0.113.9:1080:user:secret-password");

        assertThat(spec).isNotNull();
        assertThat(spec.label()).doesNotContain("secret-password");
        assertThat(spec.describe()).contains("user").doesNotContain("secret-password");
        assertThat(spec.toString()).doesNotContain("secret-password");

    }

    @Test
    void rejectsBrokenCredentialForms() {

        assertThatThrownBy(() -> TelegramAdapter.parseProxy("socks5://203.0.113.9:1080:user"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("socks5://203.0.113.9:1080:user:"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("socks5://203.0.113.9:1080:user:pa:ss"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("user:pass@host:port");
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("socks5://user:pass@203.0.113.9:1080:a:b"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("twice");

    }

    @Test
    void rejectsDoubledPortInHostPart() {

        // A pasted "host:port" into a "host:port" template yields host:port:port;
        // connecting to it would look like a dead proxy, so parsing must shout.
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("198.51.100.9:1080:1080"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("socks5://198.51.100.9:1080:1080"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> TelegramAdapter.parseProxy("http://user:pass@203.0.113.9:8080:3128"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("host");

    }

}

