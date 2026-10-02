package eu.neydev.saver.plugin.bukkit;

import com.google.inject.Guice;
import com.google.inject.Injector;
import eu.neydev.saver.app.di.Bootstrap;
import eu.neydev.saver.app.di.CoreModule;
import eu.neydev.saver.app.di.PlatformsModule;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.config.ConfigLoader;
import eu.neydev.saver.core.i18n.MessageBundleHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.logging.Level;

/**
 * The plugin entry point: the Minecraft server becomes just another host for the whole
 * bot. Configuration is the same {@code application.yml} as everywhere else - external
 * copy lives at {@code plugins/SaverBot/application.yml}, and when it is absent the
 * bundled defaults (plus environment variables) apply, exactly like the standalone jar.
 *
 * <p>Lifecycle mirrors {@code Main}: CoreModule -> child(PlatformsModule + BukkitModule)
 * -> Bootstrap. {@code onDisable} stops the bot (drains downloads, closes the database)
 * without taking the server JVM down; Bootstrap.stop() is idempotent, so a later JVM
 * shutdown hook is a no-op.
 */
public final class SaverBotPlugin extends JavaPlugin {

    private Bootstrap bootstrap;

    @Override
    public void onEnable() {

        try {

            Path dataFolder = getDataFolder().toPath();
            Files.createDirectories(dataFolder);

            Path external = dataFolder.resolve("application.yml");
            AppConfig config = new ConfigLoader().load(Files.isReadable(external) ? external : null);

            if (!Files.isReadable(external)) {
                getLogger().info("No plugins/SaverBot/application.yml - running on bundled "
                        + "defaults + environment variables. Copy config/application.yml from "
                        + "the repository next to this jar to configure the bot.");
            }

            Injector injector = Guice.createInjector(new CoreModule(config, Clock.systemUTC()));
            injector = injector.createChildInjector(
                    new PlatformsModule(config, injector.getInstance(MessageBundleHolder.class)),
                    new BukkitModule(this, config));

            bootstrap = injector.getInstance(Bootstrap.class);
            bootstrap.start();

            getLogger().info("Saver Bot is running inside this server. In-game chat: "
                    + "\"!save <url>\", \"!help\"; downloads -> "
                    + dataFolder.resolve("downloads"));

        } catch (Exception e) {

            getLogger().log(Level.SEVERE, "Saver Bot did not start - the server continues "
                    + "without it", e);
            getServer().getPluginManager().disablePlugin(this);

        }

    }

    @Override
    public void onDisable() {

        Bootstrap running = bootstrap;
        bootstrap = null;

        if (running != null) {

            try {
                running.stop();
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Error while stopping Saver Bot", e);
            }

        }

    }

}
