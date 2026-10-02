package eu.neydev.saver.plugin.bukkit;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The Bukkit entry point: reads {@code config.yml}, owns the single supervised
 * {@link BotProcess} and registers {@code /saverbot}.
 *
 * <p>The plugin is a launcher and nothing more: the bot stays a separate program with
 * its own config, storage and platforms, so the very same folder works from
 * {@code start.bat}, {@code start.sh} or a service manager without the server.
 */
public final class SaverBotPlugin extends JavaPlugin {

    private BotProcess process;

    @Override
    public void onEnable() {

        saveDefaultConfig();

        process = new BotProcess(botHome(),
                getConfig().getString("launcher-jar", "saver-launcher.jar"),
                javaBinary(),
                getConfig().getBoolean("restart-on-crash", true),
                getConfig().getLong("stop-timeout-seconds", 15L),
                new ServerLog());

        PluginCommand command = getCommand("saverbot");

        if (command != null) {
            BotCommand executor = new BotCommand(process);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (!process.launcherJarPath().toFile().isFile()) {
            getLogger().warning("No " + process.launcherJarPath().getFileName()
                    + " in " + process.launcherJarPath().getParent().toAbsolutePath()
                    + ": copy the bot distribution there, then /saverbot start");
            return;
        }

        if (getConfig().getBoolean("start-on-enable", true)) {
            process.start();
        }

    }

    @Override
    public void onDisable() {
        if (process != null) {
            process.stop();
        }
    }

    /** A relative {@code bot-home} resolves against the server root, next to {@code plugins/}. */
    private Path botHome() {

        String configured = getConfig().getString("bot-home", "saver-bot");
        Path path = Path.of(configured == null || configured.isBlank() ? "saver-bot" : configured);

        return path.isAbsolute() ? path : getServer().getWorldContainer().toPath().resolve(path);

    }

    /** Empty {@code java-binary} means the java of this server; the launcher upgrades itself to 21. */
    private String javaBinary() {

        String configured = getConfig().getString("java-binary", "");

        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String executable = os.contains("win") ? "java.exe" : "java";

        return System.getProperty("java.home") + File.separator + "bin" + File.separator + executable;

    }

    /** The child console inside the server log: one prefix, both streams, no interleaving loss. */
    private final class ServerLog implements BotProcess.Log {

        @Override
        public void info(String line) {
            getLogger().info(line);
        }

        @Override
        public void warn(String line) {
            getLogger().warning(line);
        }

        @Override
        public void error(String line) {
            getLogger().severe(line);
        }

        /** Below the default console verbosity: the periodic heartbeat lives here. */
        @Override
        public void debug(String line) {
            getLogger().fine(line);
        }

    }

}
