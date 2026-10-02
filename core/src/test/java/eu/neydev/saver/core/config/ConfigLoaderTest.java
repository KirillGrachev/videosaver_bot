package eu.neydev.saver.core.config;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.extract.QualityPreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The config contract: env substitution, strict schema (a typo must fail the startup,
 * not silently drop a setting) and the parsing of every value shape the downloader
 * section accepts (sizes, durations, quality ids, proxy strings).
 */
class ConfigLoaderTest {

    private static final String MINIMAL = """
            storage:
              type: sqlite
              sqlite-path: data/test.db
            platforms:
              telegram:
                enabled: true
                token: ${TELEGRAM_BOT_TOKEN:}
            """;

    private Path write(Path dir, String yaml) throws IOException {

        Path file = dir.resolve("application.yml");
        Files.writeString(file, yaml);

        return file;

    }

    private ConfigLoader loaderWith(Map<String, String> env) {
        return new ConfigLoader(env::get);
    }

    @Test
    void theShippedDefaultConfigParses() {

        // The real app/src/main/resources/application.yml - the file the CI smoke run
        // boots from - must parse with an empty environment (every secret has a
        // ${VAR:} default). Surefire's working directory is core/, hence ../app.
        Path shipped = Path.of("..", "app", "src", "main", "resources", "application.yml");

        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isReadable(shipped),
                "shipped application.yml not found (module-only run?)");

        AppConfig config = new ConfigLoader(name -> "").load(shipped);

        assertThat(config.storage().type()).isEqualTo(AppConfig.Storage.Type.SQLITE);
        assertThat(config.downloader().workers()).isPositive();
        assertThat(config.platform(Platform.TELEGRAM).enabled()).isTrue();
        assertThat(config.locale().supported()).contains("ru", "en");
        assertThat(config.delivery().capsFor(Platform.TELEGRAM).video()).isEqualTo(50_000_000L);
        assertThat(config.downloader().maxFileBytes()).isEqualTo(500_000_000L);

    }

    @Test
    void substitutesEnvironmentVariables(@TempDir Path dir) throws IOException {

        Path file = write(dir, MINIMAL.replace("${TELEGRAM_BOT_TOKEN:}", "${TELEGRAM_BOT_TOKEN}"));

        AppConfig config = loaderWith(Map.of("TELEGRAM_BOT_TOKEN", "123:secret"))
                .load(file);

        assertThat(config.platform(Platform.TELEGRAM).token()).isEqualTo("123:secret");

    }

    @Test
    void aMissingRequiredVariableFailsTheStartup(@TempDir Path dir) throws IOException {

        Path file = write(dir, MINIMAL.replace("${TELEGRAM_BOT_TOKEN:}", "${TELEGRAM_BOT_TOKEN}"));

        assertThatThrownBy(() -> loaderWith(Map.of()).load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("TELEGRAM_BOT_TOKEN");

    }

    @Test
    void unknownKeysFailFastAtEveryLevel(@TempDir Path dir) throws IOException {

        assertThatThrownBy(() -> new ConfigLoader(n -> "")
                .load(write(dir, MINIMAL + "\ndownloaderr:\n  workers: 2\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("unknown top-level key");

        assertThatThrownBy(() -> new ConfigLoader(n -> "")
                .load(write(dir, MINIMAL + "\ndownloader:\n  workerz: 2\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("downloader.workerz");

        assertThatThrownBy(() -> new ConfigLoader(n -> "")
                .load(write(dir, MINIMAL + "\nplatforms:\n  telegram:\n    tokn: x\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("platforms.telegram.tokn");

    }

    @Test
    void parsesSizesDurationsAndQuality(@TempDir Path dir) throws IOException {

        Path file = write(dir, MINIMAL + """

                downloader:
                  workers: 8
                  max-file-size: 1gb
                  extract-timeout: 90s
                  download-timeout: 30m
                  default-quality: "720"
                  vault:
                    dir: /tmp/vault
                    max-size: 5gb
                    ttl: 15m
                limits:
                  bytes-per-user-per-day: 500mb
                """);

        AppConfig config = new ConfigLoader(n -> "").load(file);

        assertThat(config.downloader().workers()).isEqualTo(8);
        assertThat(config.downloader().maxFileBytes()).isEqualTo(1_000_000_000L);
        assertThat(config.downloader().extractTimeout().toSeconds()).isEqualTo(90);
        assertThat(config.downloader().downloadTimeout().toMinutes()).isEqualTo(30);
        assertThat(config.downloader().defaultQuality()).isEqualTo(QualityPreset.P720);
        assertThat(config.downloader().vault().maxBytes()).isEqualTo(5_000_000_000L);
        assertThat(config.limits().bytesPerUserPerDay()).isEqualTo(500_000_000L);

    }

    @Test
    void deliveryCapsAcceptScalarsAndMaps(@TempDir Path dir) throws IOException {

        Path file = write(dir, MINIMAL + """

                delivery:
                  link-fallback: false
                  caps:
                    discord: 8mb
                    telegram: { photo: 5mb, video: 40mb }
                """);

        AppConfig config = new ConfigLoader(n -> "").load(file);

        assertThat(config.delivery().linkFallback()).isFalse();
        assertThat(config.delivery().capsFor(Platform.DISCORD).video()).isEqualTo(8_000_000L);
        assertThat(config.delivery().capsFor(Platform.DISCORD).photo()).isEqualTo(8_000_000L);
        assertThat(config.delivery().capsFor(Platform.TELEGRAM).photo()).isEqualTo(5_000_000L);
        assertThat(config.delivery().capsFor(Platform.TELEGRAM).video()).isEqualTo(40_000_000L);
        // audio/gif/document not specified: inherit the platform's own defaults
        assertThat(config.delivery().capsFor(Platform.TELEGRAM).document()).isPositive();

    }

    @Test
    void invalidProxyFailsTheStartup(@TempDir Path dir) throws IOException {

        Path file = write(dir, MINIMAL + "\ndownloader:\n  proxy: socks5://host\n");

        assertThatThrownBy(() -> new ConfigLoader(n -> "").load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("downloader.proxy");

    }

    @Test
    void displayNamesMustBeSupportedLanguages(@TempDir Path dir) throws IOException {

        Path file = write(dir, MINIMAL + """

                locale:
                  default: en
                  supported: [en]
                  display-names:
                    en: English
                    de: Deutsch
                """);

        assertThatThrownBy(() -> new ConfigLoader(n -> "").load(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("display-names.de");

    }

    @Test
    void unknownPlatformAndCapKeysFail(@TempDir Path dir) throws IOException {

        assertThatThrownBy(() -> new ConfigLoader(n -> "")
                .load(write(dir, MINIMAL + "\nplatforms:\n  icq:\n    enabled: true\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("platforms.icq");

        assertThatThrownBy(() -> new ConfigLoader(n -> "")
                .load(write(dir, MINIMAL + "\ndelivery:\n  caps:\n    telegram: { hologram: 1mb }\n")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("hologram");

    }

    @Test
    void ownersAndDisabledSourcesAreRead(@TempDir Path dir) throws IOException {

        Path file = write(dir, MINIMAL + """

                owners: [telegram:111, vk:222]
                sources:
                  disabled: [tiktok, instagram]
                """);

        AppConfig config = new ConfigLoader(n -> "").load(file);

        assertThat(config.ownerKeys()).containsExactly("telegram:111", "vk:222");
        assertThat(config.sources().disabled()).containsExactly("tiktok", "instagram");

    }

    @Test
    void placeholdersPullValuesFromTheEnvironment(@TempDir Path dir) throws IOException {

        Map<String, String> env = new HashMap<>();
        env.put("DOWNLOADER_PROXY", "http://127.0.0.1:8080");

        Path file = write(dir, MINIMAL + "\ndownloader:\n  proxy: ${DOWNLOADER_PROXY:}\n");

        AppConfig config = loaderWith(env).load(file);

        assertThat(config.downloader().proxy()).isEqualTo("http://127.0.0.1:8080");

        // Without the variable the empty default applies - the key stays optional.
        AppConfig fallback = loaderWith(Map.of()).load(file);

        assertThat(fallback.downloader().proxy()).isEmpty();

    }

}
