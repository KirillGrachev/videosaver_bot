package eu.neydev.saver.plugin.bukkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/**
 * {@code /saverbot status|start|stop|restart}: the same lifecycle the server console
 * performs on enable and disable, available in game to an operator so the bot can be
 * tested next to the other plugins without touching the server process.
 */
public final class BotCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ACTIONS = List.of("status", "start", "stop", "restart");

    private final BotProcess process;

    public BotCommand(BotProcess process) {
        this.process = process;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (action) {

            case "status" -> sender.sendMessage(status());

            case "start" -> {
                process.start();
                sender.sendMessage("saver bot: start requested, see the server log");
            }

            case "stop" -> {
                process.stop();
                sender.sendMessage("saver bot: stopped");
            }

            case "restart" -> {
                process.restart();
                sender.sendMessage("saver bot: restarted, see the server log");
            }

            default -> sender.sendMessage("usage: /saverbot status|start|stop|restart");

        }

        return true;

    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();

    }

    private String status() {

        if (!process.running()) {
            return "saver bot: not running";
        }

        String base = "saver bot: running (pid " + process.pid() + ", up "
                + process.uptimeSeconds() + " s)";
        long age = process.heartbeatAgeSeconds();

        if (age < 0) {
            return base + "; no heartbeat yet";
        }

        if (process.heartbeatStale()) {
            // The process exists but the bot stopped reporting: a hung JVM looks exactly
            // like this, and "running" alone would hide it.
            return base + "; HEARTBEAT STALE (" + age + " s ago) - the process is alive but the"
                    + " bot is not reporting, consider /saverbot restart"
                    + "\n  last: " + process.lastHeartbeat();
        }

        return base + "; heartbeat " + age + " s ago"
                + "\n  last: " + process.lastHeartbeat();

    }

}
