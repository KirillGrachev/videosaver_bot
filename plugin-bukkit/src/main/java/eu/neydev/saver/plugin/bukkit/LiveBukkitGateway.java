package eu.neydev.saver.plugin.bukkit;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The thin live glue over the Paper API. No logic lives here - only translation to and
 * from Bukkit types ({@link BukkitGateway} is the tested seam).
 */
final class LiveBukkitGateway implements BukkitGateway {

    private final Plugin plugin;
    private volatile BukkitTask heartbeatTask;

    LiveBukkitGateway(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onChat(Consumer<ChatMessage> handler) {

        plugin.getServer().getPluginManager().registerEvents(new Listener() {

            // MONITOR + ignoreCancelled: the bot reacts to chat the server actually
            // accepted, after mute/filter plugins have had their say.
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onAsyncChat(AsyncChatEvent event) {

                String text = PlainTextComponentSerializer.plainText().serialize(event.message());
                handler.accept(new ChatMessage(
                        event.getPlayer().getUniqueId(), event.getPlayer().getName(), text));

            }

        }, plugin);

    }

    @Override
    public boolean isOnline(UUID playerId) {
        return plugin.getServer().getPlayer(playerId) != null;
    }

    @Override
    public void sendToPlayer(UUID playerId, String text) {

        // execute() runs on dispatcher worker threads; the Bukkit API is main-thread.
        plugin.getServer().getScheduler().runTask(plugin, () -> {

            Player player = plugin.getServer().getPlayer(playerId);

            if (player != null) {
                player.sendMessage(Component.text(text));
            } else {
                console("[SaverBot -> " + playerId + " (left the server)] " + text);
            }

        });

    }

    @Override
    public void console(String text) {
        plugin.getServer().getConsoleSender().sendMessage(Component.text(text));
    }

    @Override
    public void scheduleRepeating(Runnable task, long periodTicks) {
        heartbeatTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, task, periodTicks, periodTicks);
    }

    @Override
    public void cancelScheduled() {

        BukkitTask task = heartbeatTask;

        if (task != null) {
            task.cancel();
            heartbeatTask = null;
        }

    }

}
