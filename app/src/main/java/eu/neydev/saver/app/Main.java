package eu.neydev.saver.app;

import com.google.inject.Guice;
import com.google.inject.Injector;
import eu.neydev.saver.app.di.Bootstrap;
import eu.neydev.saver.app.di.CoreModule;
import eu.neydev.saver.app.di.PlatformsModule;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Composition root: reads the configuration, assembles the Guice graph
 * (core + active platforms) and hands control to {@link Bootstrap}.
 * No manual service assembly in main - only configuring modules.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {

        int exitCode = 0;

        try {

            AppConfig config = new ConfigLoader().load(externalConfigPath());
            log.info("Configuration loaded: storage={}, webapp={}, quality={}",
                    config.storage().type(),
                    config.webApp().enabled(),
                    config.downloader().defaultQuality().id());

            Injector injector = Guice.createInjector(
                    new CoreModule(config, Clock.systemUTC()));
            injector = injector.createChildInjector(
                    new PlatformsModule(config, injector.getInstance(
                            eu.neydev.saver.core.i18n.MessageBundleHolder.class)));

            Bootstrap bootstrap = injector.getInstance(Bootstrap.class);

            try {
                bootstrap.start();
                bootstrap.awaitShutdown();
            } catch (Exception e) {
                exitCode = 1;
                log.error("Critical startup error", e);
            } finally {
                bootstrap.stop();
            }

        } catch (Exception e) {
            exitCode = 1;
            log.error("The bot did not start: {}", e.getMessage(), e);
        }

        // A non-zero code is the only signal a supervisor gets. Returning normally from
        // main exits with 0, and the Minecraft launcher plugin reads 0 as "exited on its
        // own" and does not restart - a fatal configuration error would kill the bot
        // forever under a calm log line.
        if (exitCode != 0) {
            System.exit(exitCode);
        }

    }

    private static Path externalConfigPath() {
        Path path = Path.of("config/application.yml");
        return Files.isReadable(path) ? path : null;
    }

}
