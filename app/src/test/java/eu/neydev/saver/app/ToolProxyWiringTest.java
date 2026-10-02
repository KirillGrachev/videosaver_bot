package eu.neydev.saver.app;

import com.google.inject.Guice;
import com.google.inject.Injector;
import eu.neydev.saver.app.di.CoreModule;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.backend.GalleryDlExtractor;
import eu.neydev.saver.core.extract.backend.YtDlpExtractor;
import eu.neydev.saver.core.security.SsrfFilterProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression guard for the port -1 incident: the extraction tools bake the SSRF
 * filter proxy address into their command lines AT GRAPH CONSTRUCTION TIME, so the
 * proxy has to be listening before the injector finishes assembling - a proxy that
 * starts later hands every yt-dlp/gallery-dl job an unroutable http://127.0.0.1:-1.
 */
class ToolProxyWiringTest {

    @TempDir
    Path tempDir;

    @Test
    void extractorsBakeTheLiveProxyPortNotMinusOne() {

        Injector injector = Guice.createInjector(
                new CoreModule(TestConfigs.minimal(tempDir), Clock.systemUTC()));

        try {

            SsrfFilterProxy proxy = injector.getInstance(SsrfFilterProxy.class);

            assertThat(proxy.port()).isPositive();
            assertThat(proxy.url()).doesNotEndWith(":-1");

            assertThat(injector.getInstance(YtDlpExtractor.class).proxyUrl())
                    .isEqualTo(proxy.url());
            assertThat(injector.getInstance(GalleryDlExtractor.class).proxyUrl())
                    .isEqualTo(proxy.url());

        } finally {
            injector.getInstance(SsrfFilterProxy.class).close();
        }

    }

    @Test
    void anExplicitOperatorProxyWinsOverTheFilterProxy() {

        AppConfig config = TestConfigs.minimalWithProxy(tempDir, "socks5://127.0.0.1:9050");

        Injector injector = Guice.createInjector(new CoreModule(config, Clock.systemUTC()));

        try {

            assertThat(injector.getInstance(YtDlpExtractor.class).proxyUrl())
                    .isEqualTo("socks5://127.0.0.1:9050");
            assertThat(injector.getInstance(GalleryDlExtractor.class).proxyUrl())
                    .isEqualTo("socks5://127.0.0.1:9050");

        } finally {
            injector.getInstance(SsrfFilterProxy.class).close();
        }

    }

}
