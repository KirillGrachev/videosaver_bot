package eu.neydev.saver.plugin.bukkit;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The ONLY Bukkit-API surface the adapter logic touches. Everything with logic
 * ({@link BukkitChatMapper}, {@link BukkitAdapter}) is written against this seam and
 * unit-tested with a fake; {@link LiveBukkitGateway} is the thin, untestable glue that
 * runs inside a real server. Same honesty rule as the platform adapters: the transport
 * is thin, the mapping is tested.
 */
public interface BukkitGateway {

    /** One in-game chat message: who said what. */
    record ChatMessage(UUID playerId, String playerName, String text) {
    }

    /** Subscribe to async chat events. Called once, from {@code onEnable}. */
    void onChat(Consumer<ChatMessage> handler);

    boolean isOnline(UUID playerId);

    /** Thread-safe: implementations marshal to the server thread when required. */
    void sendToPlayer(UUID playerId, String text);

    /** Messages that cannot reach a player (offline, service chat ids) go to the console. */
    void console(String text);

    /** Periodic async task (transport heartbeat). */
    void scheduleRepeating(Runnable task, long periodTicks);

    void cancelScheduled();

}
