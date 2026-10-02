package eu.neydev.saver.app;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.config.ConfigLoader;
import eu.neydev.saver.core.extract.QualityPreset;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-drift gate for configuration defaults. Defaults live in two places by design:
 * the shipped application.yml (what an operator reads) and ConfigLoader (what a
 * partial external config falls back to). When they diverge, an operator who removed
 * a line gets different behavior than the file they read promised. This test parses
 * the SHIPPED yml and pins the values that must stay in sync with the loader defaults.
 */
class ConfigDefaultsTest {

    private static final Path SHIPPED =
            Path.of("src", "main", "resources", "application.yml");

    private final AppConfig config = new ConfigLoader(name -> null).load(SHIPPED);

    @Test
    void downloaderDefaultsMatchTheLoader() {

        AppConfig.Downloader d = config.downloader();

        assertThat(d.workers()).isEqualTo(4);
        assertThat(d.queueCapacity()).isEqualTo(200);
        assertThat(d.perUserConcurrent()).isEqualTo(2);
        assertThat(d.maxItemsPerRequest()).isEqualTo(10);
        assertThat(d.extractTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(d.downloadTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(d.maxFileBytes()).isEqualTo(500_000_000L);
        assertThat(d.defaultQuality()).isEqualTo(QualityPreset.BEST);
        assertThat(d.allowPrivateNetworks()).isFalse();
        assertThat(d.cookiesOwnerOnly()).isTrue();
        assertThat(d.perSourcePerSecond()).isEqualTo(2.0);
        assertThat(d.sleepRequests()).isEqualTo(Duration.ofMillis(500));
        assertThat(d.jobLogRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(d.shutdownDrain()).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.writeSubs()).isFalse();
        assertThat(d.audioThumbnail()).isFalse();
        assertThat(d.blockedExtensions()).contains("exe", "bat", "ps1");
        assertThat(d.tools().provision()).isTrue();
        assertThat(d.tools().provisionDir()).isEqualTo("tools");
        assertThat(d.tools().updateInterval()).isZero();

    }

    @Test
    void vaultAndLimitsDefaultsMatchTheLoader() {

        assertThat(config.downloader().vault().dir()).isEqualTo("data/media");
        assertThat(config.downloader().vault().maxBytes()).isEqualTo(10_000_000_000L);
        assertThat(config.downloader().vault().ttl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.downloader().vault().sendGrace()).isEqualTo(Duration.ofMinutes(10));

        assertThat(config.limits().jobsPerUserPerHour()).isEqualTo(20);
        assertThat(config.limits().jobsPerUserPerDay()).isEqualTo(200);
        assertThat(config.limits().bytesPerUserPerDay()).isEqualTo(2_000_000_000L);

    }

    @Test
    void deliveryCapsMatchThePublishedPlatformLimits() {

        Map<Platform, AppConfig.Delivery.SizeCaps> caps = config.delivery().caps();

        assertThat(caps.get(Platform.TELEGRAM).video()).isEqualTo(50_000_000L);
        assertThat(caps.get(Platform.TELEGRAM).photo()).isEqualTo(10_000_000L);
        assertThat(caps.get(Platform.VK).video()).isEqualTo(200_000_000L);
        assertThat(caps.get(Platform.DISCORD).video()).isEqualTo(25_000_000L);
        assertThat(caps.get(Platform.SLACK).video()).isEqualTo(100_000_000L);
        assertThat(caps.get(Platform.WHATSAPP).video()).isEqualTo(16_000_000L);
        assertThat(caps.get(Platform.WHATSAPP).document()).isEqualTo(100_000_000L);
        // Viber: static picture 1 MB, video 26 MB, files 50 MB (developers.viber.com)
        assertThat(caps.get(Platform.VIBER).photo()).isEqualTo(1_000_000L);
        assertThat(caps.get(Platform.VIBER).video()).isEqualTo(26_000_000L);
        assertThat(caps.get(Platform.VIBER).document()).isEqualTo(50_000_000L);

        assertThat(config.delivery().linkFallback()).isTrue();

    }

    @Test
    void pipelineWebappAndStatusDefaultsMatchTheLoader() {

        assertThat(config.pipeline().inboundQueueCapacity()).isEqualTo(10_000);
        assertThat(config.pipeline().workerThreads()).isEqualTo(16);
        assertThat(config.pipeline().inboundPerUserPerWindow()).isEqualTo(8);
        assertThat(config.pipeline().outboundWorkersPerPlatform()).isEqualTo(4);
        assertThat(config.pipeline().outboundGlobalPerSecond()).isEqualTo(24.0);
        assertThat(config.pipeline().outboundPerChatPerMinute()).isEqualTo(18);
        assertThat(config.pipeline().sendTimeout()).isEqualTo(java.time.Duration.ofSeconds(15));

        assertThat(config.webApp().enabled()).isFalse();
        assertThat(config.webApp().bindHost()).isEqualTo("127.0.0.1");
        assertThat(config.webApp().port()).isEqualTo(8080);
        assertThat(config.webApp().mediaLinkTtl()).isEqualTo(java.time.Duration.ofMinutes(5));

        assertThat(config.status().heartbeatInterval()).isEqualTo(java.time.Duration.ofMinutes(5));
        assertThat(config.status().livenessFile()).isEqualTo("data/bot.liveness");

        assertThat(config.storage().sqlitePath()).isEqualTo("data/saver.db");
        assertThat(config.storage().poolSize()).isEqualTo(8);

        assertThat(config.downloader().tools().ytDlp()).isEqualTo("yt-dlp");
        assertThat(config.downloader().tools().ffprobe()).isEqualTo("ffprobe");
        assertThat(config.downloader().tools().updateInterval()).isEqualTo(java.time.Duration.ZERO);

    }

    @Test
    void localeSetShipsEveryBundledLanguage() {

        assertThat(config.locale().supported()).containsExactly(
                "ru", "uk", "be", "en", "de", "es", "fr", "it", "pt", "pl", "cs", "sk",
                "bg", "hr", "sl", "nl", "da", "sv", "fi", "et", "lv", "lt", "ro", "hu",
                "el", "ga", "mt", "kk", "ky", "tg", "tk", "uz", "az", "ka", "hy");
        assertThat(config.locale().displayNames())
                .containsKeys("ru", "en", "uk", "ga", "mt", "ka", "hy", "uz");

    }

}
