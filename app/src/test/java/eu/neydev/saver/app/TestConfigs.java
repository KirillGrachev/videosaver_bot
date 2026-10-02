package eu.neydev.saver.app;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.util.ByteFormat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One config shape shared by the app-level graph tests: everything disabled or
 * temp-dir-bound, so a test run touches no real file outside its @TempDir and opens
 * no port.
 */
final class TestConfigs {

    private TestConfigs() {
    }

    static AppConfig minimal(Path tempDir) {

        return new AppConfig(
                new AppConfig.Storage(AppConfig.Storage.Type.SQLITE,
                        tempDir.resolve("di.db").toString(), null, null, null, 2),
                new AppConfig.Downloader(1, 10, 2, 5,
                        Duration.ofSeconds(10), Duration.ofSeconds(20),
                        ByteFormat.parse("100mb", "t"), QualityPreset.BEST,
                        false, null, null,
                        true, 1000.0, Duration.ZERO, Duration.ofDays(90),
                        Duration.ofSeconds(2), false, false,
                        AppConfig.Downloader.DEFAULT_BLOCKED_EXTENSIONS,
                        new AppConfig.Downloader.Tools("missing-ytdlp", "missing-gallerydl",
                                "missing-ffmpeg", "missing-ffprobe", Duration.ZERO),
                        new AppConfig.Downloader.Vault(tempDir.resolve("vault").toString(),
                                ByteFormat.parse("100mb", "t"), Duration.ofMinutes(5),
                                Duration.ofSeconds(1))),
                AppConfig.Limits.DEFAULT,
                new AppConfig.Delivery(Map.of(), true),
                new AppConfig.Pipeline(100, 2, 50, 1, 50, 50, Duration.ofSeconds(5)),
                new AppConfig.WebApp(false, "127.0.0.1", 18080, null,
                        Duration.ofMinutes(5), null),
                new AppConfig.Locale("ru", Set.of("ru", "en")),
                new AppConfig.Sources(List.of()),
                new AppConfig.Community("https://github.com/example/repo"),
                Map.of(),
                List.of(),
                // no liveness file in tests: the heartbeat must not write into the tree
                new AppConfig.Status(Duration.ofMinutes(5), null));

    }

    static AppConfig withPlatform(AppConfig base, Platform platform, String token) {

        Map<Platform, AppConfig.PlatformSection> platforms =
                new java.util.EnumMap<>(Platform.class);
        platforms.putAll(base.platforms());
        platforms.put(platform, new AppConfig.PlatformSection(true, token, "1", null, null));

        return new AppConfig(base.storage(), base.downloader(), base.limits(), base.delivery(),
                base.pipeline(), base.webApp(), base.locale(), base.sources(), base.community(),
                platforms, base.ownerKeys(), base.status());

    }

}
