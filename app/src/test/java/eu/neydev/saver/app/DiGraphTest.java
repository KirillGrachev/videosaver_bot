package eu.neydev.saver.app;

import com.google.inject.Guice;
import com.google.inject.Injector;
import eu.neydev.saver.app.di.Bootstrap;
import eu.neydev.saver.app.di.CoreModule;
import eu.neydev.saver.app.di.PlatformsModule;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DI graph test: the injector is assembled without active platforms, all singletons
 * are bound, Bootstrap starts and stops without side effects.
 */
class DiGraphTest {

    @TempDir
    Path tempDir;

    private AppConfig config() {
        return TestConfigs.minimal(tempDir);
    }

    @Test
    void graphBuildsAndBootstrapLifecycleWorks() throws Exception {

        Injector injector = Guice.createInjector(
                new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(new PlatformsModule(config(),
                injector.getInstance(eu.neydev.saver.core.i18n.MessageBundleHolder.class)));

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);
        assertThat(bootstrap.adapters()).isEmpty();

        bootstrap.start();
        bootstrap.stop();

    }

    @Test
    void inactivePlatformsProduceNoAdapters() {

        Injector injector = Guice.createInjector(
                new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(new PlatformsModule(config(),
                injector.getInstance(eu.neydev.saver.core.i18n.MessageBundleHolder.class)));

        Set<PlatformAdapter> adapters = full.getInstance(
                new com.google.inject.Key<>() {
                });

        assertThat(adapters).isEmpty();

    }

    @Test
    void aPlatformWithATokenButMissingRequirementsIsSkippedNotCrashed() {

        // VK requires a group id: enabled + token alone must degrade, not throw.
        AppConfig base = config();

        Map<Platform, AppConfig.PlatformSection> platforms =
                new java.util.EnumMap<>(Platform.class);
        platforms.put(Platform.VK,
                new AppConfig.PlatformSection(true, "token-without-id", null, null, null));

        AppConfig config = new AppConfig(base.storage(), base.downloader(), base.limits(),
                base.delivery(), base.pipeline(), base.webApp(), base.locale(),
                base.sources(), base.community(), platforms, base.ownerKeys(), base.status());

        Injector injector = Guice.createInjector(
                new CoreModule(config, Clock.systemUTC()));
        Injector full = injector.createChildInjector(new PlatformsModule(config,
                injector.getInstance(eu.neydev.saver.core.i18n.MessageBundleHolder.class)));

        Set<PlatformAdapter> adapters = full.getInstance(
                new com.google.inject.Key<>() {
                });

        assertThat(adapters).isEmpty();

    }

    @Test
    void platformEnumCoversAllShippedAdapters() {
        // BUKKIT is the one adapter PlatformsModule does not ship: it lives in
        // plugin-bukkit (bound by BukkitModule inside the Minecraft server), which
        // depends on this module - a dependency the other way round would be a cycle.
        assertThat(Platform.values()).containsExactly(
                Platform.TELEGRAM, Platform.VK, Platform.DISCORD,
                Platform.SLACK, Platform.WHATSAPP, Platform.VIBER, Platform.BUKKIT);
    }

}
