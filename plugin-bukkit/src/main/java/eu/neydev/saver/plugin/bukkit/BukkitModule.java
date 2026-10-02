package eu.neydev.saver.plugin.bukkit;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.config.AppConfig;
import org.bukkit.plugin.Plugin;

/**
 * Binds the in-game chat adapter into the same {@code Set<PlatformAdapter>} multibinder
 * the messenger platforms use, so {@code Bootstrap} starts it with the rest. Installing
 * the plugin IS the intent - the adapter is always bound; the config section only
 * tunes it: {@code platforms.bukkit.id} overrides the chat prefix (default "!").
 */
public final class BukkitModule extends AbstractModule {

    private final Plugin plugin;
    private final AppConfig config;

    public BukkitModule(Plugin plugin, AppConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    protected void configure() {

        AppConfig.PlatformSection section = config.platform(Platform.BUKKIT);

        String prefix = section.extraId() != null && !section.extraId().isBlank()
                ? section.extraId().strip()
                : "!";

        BukkitAdapter adapter = new BukkitAdapter(
                new LiveBukkitGateway(plugin),
                plugin.getDataFolder().toPath().resolve("downloads"),
                prefix);

        Multibinder.newSetBinder(binder(), PlatformAdapter.class)
                .addBinding().toInstance(adapter);

    }

}
