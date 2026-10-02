package eu.neydev.saver.plugin.bukkit;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.api.UpdateSink;
import eu.neydev.saver.core.media.MediaAttachment;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.metrics.PlatformHealth;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The adapter against the fake gateway: routing, media persistence, offline honesty. */
class BukkitAdapterTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** Records everything, marshals nothing - the seam is the point. */
    private static final class FakeGateway implements BukkitGateway {

        final Set<UUID> online = new HashSet<>();
        final Map<UUID, List<String>> sent = new ConcurrentHashMap<>();
        final List<String> console = new ArrayList<>();
        Consumer<ChatMessage> handler;
        Runnable scheduled;
        boolean cancelled;

        @Override
        public void onChat(Consumer<ChatMessage> handler) {
            this.handler = handler;
        }

        @Override
        public boolean isOnline(UUID playerId) {
            return online.contains(playerId);
        }

        @Override
        public void sendToPlayer(UUID playerId, String text) {
            sent.computeIfAbsent(playerId, id -> new ArrayList<>()).add(text);
        }

        @Override
        public void console(String text) {
            console.add(text);
        }

        @Override
        public void scheduleRepeating(Runnable task, long periodTicks) {
            this.scheduled = task;
        }

        @Override
        public void cancelScheduled() {
            cancelled = true;
        }

        void chat(UUID playerId, String text) {
            handler.accept(new ChatMessage(playerId, "Steve", text));
        }

    }

    private FakeGateway gateway;
    private BukkitAdapter adapter;
    private PlatformHealth health;
    private final List<IncomingUpdate> received = new ArrayList<>();

    @TempDir
    Path downloads;

    @BeforeEach
    void setUp() {

        gateway = new FakeGateway();
        adapter = new BukkitAdapter(gateway, downloads, "!");
        health = new PlatformHealth();

        UpdateSink sink = received::add;
        adapter.start(new PlatformContext(sink, health));

    }

    // ---------- inbound ----------

    @Test
    void prefixedCommandReachesThePipelineInSlashForm() {

        gateway.chat(PLAYER, "!start");

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).isInstanceOfSatisfying(IncomingUpdate.TextMessage.class,
                message -> {
                    assertThat(message.text()).isEqualTo("/start");
                    assertThat(message.chatId()).isEqualTo(PLAYER.toString());
                    assertThat(message.user().key()).isEqualTo("bukkit:" + PLAYER);
                });

    }

    @Test
    void bareUrlReachesThePipelineAsFreeText() {

        gateway.chat(PLAYER, "!https://youtu.be/x");

        assertThat(received).hasSize(1);
        assertThat(((IncomingUpdate.TextMessage) received.getFirst()).text())
                .isEqualTo("https://youtu.be/x");

    }

    @Test
    void unprefixedChatIsIgnored() {

        gateway.chat(PLAYER, "gg wp");

        assertThat(received).isEmpty();

    }

    @Test
    void callbackPressBecomesACallbackUpdate() {

        gateway.chat(PLAYER, "!cb quality_720");

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).isInstanceOfSatisfying(IncomingUpdate.Callback.class,
                callback -> {
                    assertThat(callback.actionId()).isEqualTo("quality_720");
                    assertThat(callback.interactionId()).isNotBlank();
                });

    }

    @Test
    void inboundEventsMarkThePlatformAlive() {

        gateway.chat(PLAYER, "!help");

        assertThat(health.idle(Platform.BUKKIT, Instant.now())).isLessThan(Duration.ofMinutes(1));

    }

    @Test
    void scheduledHeartbeatBeatsWithoutAnyChat() {

        PlatformHealth fresh = new PlatformHealth();
        BukkitAdapter rested = new BukkitAdapter(gateway, downloads, "!");
        rested.start(new PlatformContext(update -> { }, fresh));

        gateway.scheduled.run();

        assertThat(fresh.idle(Platform.BUKKIT, Instant.now())).isLessThan(Duration.ofMinutes(1));

    }

    @Test
    void eventsAfterStopAreDropped() {

        adapter.stop();
        gateway.chat(PLAYER, "!start");

        assertThat(received).isEmpty();
        assertThat(gateway.cancelled).isTrue();

    }

    // ---------- outbound ----------

    @Test
    void textGoesToTheOnlinePlayer() {

        gateway.online.add(PLAYER);

        SendResult result = adapter.execute(new OutboundMessage.Send(
                Platform.BUKKIT, PLAYER.toString(), RichText.plain("Hello"), InlineKeyboard.empty()));

        assertThat(result).isEqualTo(SendResult.ack());
        assertThat(gateway.sent.get(PLAYER)).containsExactly("Hello");

    }

    @Test
    void textForAnOfflinePlayerGoesToTheConsole() {

        adapter.execute(new OutboundMessage.Send(
                Platform.BUKKIT, PLAYER.toString(), RichText.plain("Hello"), InlineKeyboard.empty()));

        assertThat(gateway.sent).isEmpty();
        assertThat(gateway.console).singleElement().asString().contains("Hello").contains(PLAYER.toString());

    }

    @Test
    void editBecomesAFreshMessage() {

        gateway.online.add(PLAYER);

        adapter.execute(new OutboundMessage.Edit(
                Platform.BUKKIT, PLAYER.toString(), "42", RichText.plain("Menu v2"), InlineKeyboard.empty()));

        assertThat(gateway.sent.get(PLAYER)).containsExactly("Menu v2");

    }

    @Test
    void answerCallbackWithTextIsDeliveredBlankIsNot() {

        gateway.online.add(PLAYER);

        adapter.execute(new OutboundMessage.AnswerCallback(
                Platform.BUKKIT, PLAYER.toString(), "i1", "Saved!", false));
        adapter.execute(new OutboundMessage.AnswerCallback(
                Platform.BUKKIT, PLAYER.toString(), "i2", "  ", false));

        assertThat(gateway.sent.get(PLAYER)).containsExactly("Saved!");

    }

    @Test
    void deleteIsAnHonestNoOp() {

        gateway.online.add(PLAYER);

        adapter.execute(new OutboundMessage.Delete(Platform.BUKKIT, PLAYER.toString(), "42"));

        assertThat(gateway.sent).isEmpty();
        assertThat(gateway.console).isEmpty();

    }

    @Test
    void mediaIsCopiedToThePlayersDownloadFolder(@TempDir Path vault) throws IOException {

        gateway.online.add(PLAYER);

        Path source = vault.resolve("clip.mp4");
        Files.writeString(source, "bytes");
        MediaAttachment attachment = MediaAttachment.of(
                MediaKind.VIDEO, source, "Clip", null, null, 12, "https://youtu.be/x");

        adapter.execute(new OutboundMessage.SendMedia(
                Platform.BUKKIT, PLAYER.toString(), RichText.plain("Done"),
                InlineKeyboard.empty(), attachment));

        Path saved = downloads.resolve(PLAYER.toString()).resolve("clip.mp4");
        assertThat(saved).exists();
        assertThat(Files.readString(saved)).isEqualTo("bytes");

        assertThat(gateway.sent.get(PLAYER)).singleElement().asString()
                .contains("Done")
                .contains("clip.mp4")
                .contains(PLAYER.toString());

    }

    @Test
    void mediaForAnOfflinePlayerIsStillSavedAndReportedToConsole(@TempDir Path vault) throws IOException {

        Path source = vault.resolve("pic.png");
        Files.writeString(source, "png");
        MediaAttachment attachment = MediaAttachment.of(
                MediaKind.PHOTO, source, null, null, null, null, null);

        adapter.execute(new OutboundMessage.SendMedia(
                Platform.BUKKIT, PLAYER.toString(), RichText.plain("Done"),
                InlineKeyboard.empty(), attachment));

        assertThat(downloads.resolve(PLAYER.toString()).resolve("pic.png")).exists();
        assertThat(gateway.console).singleElement().asString().contains("pic.png");

    }

    @Test
    void mediaGroupsAreStoredFileByFile(@TempDir Path vault) throws IOException {

        gateway.online.add(PLAYER);

        Path first = vault.resolve("a.jpg");
        Path second = vault.resolve("b.jpg");
        Files.writeString(first, "a");
        Files.writeString(second, "b");

        adapter.execute(new OutboundMessage.SendMediaGroup(
                Platform.BUKKIT, PLAYER.toString(), RichText.plain("Album"), InlineKeyboard.empty(),
                List.of(MediaAttachment.of(MediaKind.PHOTO, first, null, null, null, null, null),
                        MediaAttachment.of(MediaKind.PHOTO, second, null, null, null, null, null)),
                false));

        assertThat(downloads.resolve(PLAYER.toString()).resolve("a.jpg")).exists();
        assertThat(downloads.resolve(PLAYER.toString()).resolve("b.jpg")).exists();
        assertThat(gateway.sent.get(PLAYER)).singleElement().asString().contains("a.jpg").contains("b.jpg");

    }

    @Test
    void hostileFileNamesCannotEscapeTheDownloadFolder(@TempDir Path vault) throws IOException {

        gateway.online.add(PLAYER);

        Path source = vault.resolve("safe-source.bin");
        Files.writeString(source, "x");
        MediaAttachment attachment = new MediaAttachment(
                MediaKind.DOCUMENT, source, "../../evil.txt", "application/octet-stream", 1,
                null, null, null, null, null, null);

        adapter.execute(new OutboundMessage.SendMedia(
                Platform.BUKKIT, PLAYER.toString(), RichText.plain("Done"),
                InlineKeyboard.empty(), attachment));

        assertThat(downloads.resolve(PLAYER.toString()).resolve(".._.._evil.txt")).exists();
        assertThat(downloads.getParent().resolve("evil.txt")).doesNotExist();

    }

    @Test
    void unwritableDownloadFolderSurfacesAsAPlatformException(@TempDir Path vault) throws IOException {

        // downloads points at a REGULAR FILE, so createDirectories must fail.
        Path blocker = downloads.resolve("file.txt");
        Files.writeString(blocker, "not a directory");

        BukkitAdapter broken = new BukkitAdapter(gateway, blocker, "!");
        gateway.online.add(PLAYER);

        Path source = vault.resolve("clip.mp4");
        Files.writeString(source, "x");

        assertThatThrownBy(() -> broken.execute(new OutboundMessage.SendMedia(
                Platform.BUKKIT, PLAYER.toString(), RichText.plain("Done"), InlineKeyboard.empty(),
                MediaAttachment.of(MediaKind.VIDEO, source, null, null, null, null, null))))
                .isInstanceOf(PlatformException.class);

    }

    @Test
    void platformContractIsHonest() {

        assertThat(adapter.platform()).isEqualTo(Platform.BUKKIT);
        assertThat(adapter.isEnabled()).isTrue();
        assertThat(adapter.supportsEdits()).isFalse();
        assertThat(adapter.supportsMediaGroups()).isFalse();

    }

}
