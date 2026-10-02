package eu.neydev.saver.plugin.bukkit;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.api.UpdateSink;
import eu.neydev.saver.core.media.MediaAttachment;
import eu.neydev.saver.core.text.RichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The Minecraft-chat platform adapter, and an honest one: the in-game chat cannot
 * display videos, so media "delivery" means copying the finished file to
 * {@code plugins/SaverBot/downloads/<player-uuid>/} and telling the player where it
 * landed. Text and rendered menus go straight to the chat; when the player is already
 * offline the message goes to the server console instead - the file stays on disk
 * either way, nothing is silently lost.
 *
 * <p>Chat id = the player's UUID. There are no native edits and no albums here
 * ({@link #supportsEdits()} = false), so the job manager streams progress the same way
 * it does for WhatsApp/Viber and the dispatcher expands media groups per file.
 */
public final class BukkitAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(BukkitAdapter.class);

    /** 30 s in ticks: the transport is passive (chat events), the beat proves it lives. */
    private static final long HEARTBEAT_PERIOD_TICKS = 20L * 30;

    private final BukkitGateway gateway;
    private final Path downloadsRoot;
    private final String prefix;

    /** Set in {@link #start}, cleared in {@link #stop}; chat events outside are dropped. */
    private volatile UpdateSink sink;

    public BukkitAdapter(BukkitGateway gateway, Path downloadsRoot, String prefix) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.downloadsRoot = Objects.requireNonNull(downloadsRoot, "downloadsRoot");
        this.prefix = prefix == null || prefix.isBlank() ? "!" : prefix;
    }

    @Override
    public Platform platform() {
        return Platform.BUKKIT;
    }

    /** The adapter exists because the plugin was installed - that IS the enablement. */
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean supportsEdits() {
        return false;
    }

    @Override
    public boolean supportsMediaGroups() {
        return false;
    }

    @Override
    public void start(PlatformContext context) {

        sink = context.instrumentedSink(Platform.BUKKIT);
        gateway.onChat(this::onChatMessage);
        gateway.scheduleRepeating(() -> context.health().beat(Platform.BUKKIT), HEARTBEAT_PERIOD_TICKS);

        log.info("Bukkit platform active: chat prefix '{}', downloads -> {}", prefix, downloadsRoot);

    }

    @Override
    public void stop() {
        gateway.cancelScheduled();
        sink = null;
    }

    private void onChatMessage(BukkitGateway.ChatMessage message) {

        UpdateSink target = sink;

        if (target == null) {
            return;
        }

        BukkitChatMapper.parse(prefix, message.text()).ifPresent(parsed -> {

            PlatformUser user = new PlatformUser(Platform.BUKKIT, message.playerId().toString());
            String chatId = message.playerId().toString();

            IncomingUpdate update = switch (parsed) {
                case BukkitChatMapper.Parsed.Command command ->
                        new IncomingUpdate.TextMessage(user, chatId, command.coreText(), null);
                case BukkitChatMapper.Parsed.FreeText freeText ->
                        new IncomingUpdate.TextMessage(user, chatId, freeText.text(), null);
                case BukkitChatMapper.Parsed.CallbackPress press ->
                        new IncomingUpdate.Callback(user, chatId, press.actionId(),
                                "0", "bukkit-" + chatId, null);
            };

            try {
                target.accept(update);
            } catch (RuntimeException e) {
                log.error("Inbound pipeline rejected a Bukkit update from {}", chatId, e);
            }

        });

    }

    @Override
    public SendResult execute(OutboundMessage message) {

        return switch (message) {

            case OutboundMessage.Send send -> {
                deliverText(send.chatId(),
                        BukkitChatMapper.render(prefix, send.text(), send.keyboard()));
                yield SendResult.ack();
            }

            // No native edits: a menu re-render becomes a fresh message (WhatsApp-style).
            case OutboundMessage.Edit edit -> {
                deliverText(edit.chatId(),
                        BukkitChatMapper.render(prefix, edit.text(), edit.keyboard()));
                yield SendResult.ack();
            }

            case OutboundMessage.AnswerCallback answer -> {
                if (!answer.text().isBlank()) {
                    deliverText(answer.chatId(), answer.text());
                }
                yield SendResult.ack();
            }

            // The chat has no bot messages to delete - an honest no-op.
            case OutboundMessage.Delete ignored -> SendResult.ack();

            case OutboundMessage.SendMedia media -> {
                deliverMedia(media.chatId(), media.caption(), media.keyboard(), List.of(media.media()));
                yield SendResult.ack();
            }

            case OutboundMessage.SendMediaGroup group -> {
                deliverMedia(group.chatId(), group.caption(), group.keyboard(), group.media());
                yield SendResult.ack();
            }

        };

    }

    private void deliverMedia(String chatId, RichText caption,
                              InlineKeyboard keyboard, List<MediaAttachment> media) {

        StringBuilder text = new StringBuilder(BukkitChatMapper.render(prefix, caption, keyboard));

        for (MediaAttachment attachment : media) {

            Path saved = store(chatId, attachment);

            text.append("\n> ").append(saved.getFileName())
                    .append(" -> ").append(downloadsRoot.relativize(saved));

            if (attachment.publicUrl() != null) {
                text.append(" (").append(attachment.publicUrl()).append(')');
            }

        }

        deliverText(chatId, text.toString());

    }

    /** Copies the vault file to the server's download tree. The vault may delete its copy right after the send settles - ours must exist independently. */
    private Path store(String chatId, MediaAttachment attachment) {

        try {

            Path dir = downloadsRoot.resolve(sanitize(chatId));
            Files.createDirectories(dir);

            Path target = dir.resolve(sanitize(attachment.fileName()));
            Files.copy(attachment.file(), target, StandardCopyOption.REPLACE_EXISTING);

            return target;

        } catch (IOException e) {
            throw new PlatformException(
                    "Cannot save " + attachment.fileName() + " under " + downloadsRoot, e);
        }

    }

    private void deliverText(String chatId, String text) {

        UUID playerId = parseUuid(chatId);

        if (playerId != null && gateway.isOnline(playerId)) {
            gateway.sendToPlayer(playerId, text);
            return;
        }

        // Offline player (downloads outlive play sessions) or a service chat id:
        // the console copy keeps the outcome visible to the operator.
        gateway.console("[SaverBot -> " + chatId + "] " + text);

    }

    private static UUID parseUuid(String chatId) {
        try {
            return UUID.fromString(chatId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Player-supplied strings (chat id, file name) become path segments - never traversal. */
    private static String sanitize(String name) {

        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..") ? "_" : cleaned;

    }

}
