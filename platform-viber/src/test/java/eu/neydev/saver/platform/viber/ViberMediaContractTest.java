package eu.neydev.saver.platform.viber;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.media.MediaAttachment;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Viber media contract: media travels BY URL (the core publishes short-lived vault
 * links), videos carry size+duration, unknown-duration videos degrade to files, and
 * without a public URL the adapter falls back to text + the source link instead of
 * failing silently.
 */
class ViberMediaContractTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String nextResponse =
            "{\"status\":0,\"status_message\":\"ok\",\"message_token\":\"tok-1\"}";
    private String apiBase;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        server.createContext("/", exchange -> {

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);

            byte[] response = nextResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }

        });

        server.start();

    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ViberAdapter adapter(String publicUrl) {
        return new ViberAdapter("token", publicUrl, "secret", apiBase);
    }

    private static MediaAttachment attachment(Path dir, MediaKind kind, String name,
                                              Integer duration, String publicUrl)
            throws Exception {

        Path file = dir.resolve(name);
        Files.write(file, "vb".getBytes(StandardCharsets.UTF_8));

        MediaAttachment media = MediaAttachment.of(kind, file, null, 640, 360, duration,
                "https://source.example/page");

        return publicUrl == null ? media : media.withPublicUrl(publicUrl);

    }

    private static OutboundMessage.SendMedia sendMedia(MediaAttachment media) {
        return new OutboundMessage.SendMedia(Platform.VIBER, "user-1",
                RichText.plain("caption here"), InlineKeyboard.empty(), media);
    }

    @Test
    void theAdapterHonestlyDeclaresNoEditSupport() {
        assertThat(adapter("https://bot.example").supportsEdits()).isFalse();
    }

    @Test
    void picturesCarryNoSelfThumbnail(@TempDir Path dir) throws Exception {

        adapter("https://bot.example").execute(
                sendMedia(attachment(dir, MediaKind.PHOTO, "p.jpg", null,
                        "https://bot.example/media/t1")));

        // thumbnail == media would make Viber fetch the same bytes twice; the field
        // is optional and must stay absent.
        assertThat(bodies.get(0)).doesNotContain("thumbnail");

    }

    @Test
    void photosBecomePictureMessagesWithThePublicUrl(@TempDir Path dir) throws Exception {

        SendResult result = adapter("https://bot.example").execute(
                sendMedia(attachment(dir, MediaKind.PHOTO, "p.jpg", null,
                        "https://bot.example/media/abc")));

        String body = bodies.get(0);

        assertThat(body)
                .contains("\"type\":\"picture\"")
                .contains("https://bot.example/media/abc")
                .contains("caption here");
        assertThat(result.messageId()).isEqualTo("tok-1");

    }

    @Test
    void videosCarrySizeAndDuration(@TempDir Path dir) throws Exception {

        adapter("https://bot.example").execute(sendMedia(
                attachment(dir, MediaKind.VIDEO, "v.mp4", 42, "https://bot.example/media/v")));

        assertThat(bodies.get(0))
                .contains("\"type\":\"video\"")
                .contains("\"duration\":42000")
                .contains("\"size\":");

    }

    @Test
    void videosWithoutDurationDegradeToFiles(@TempDir Path dir) throws Exception {

        adapter("https://bot.example").execute(sendMedia(
                attachment(dir, MediaKind.VIDEO, "v.mp4", null, "https://bot.example/media/v2")));

        assertThat(bodies.get(0))
                .contains("\"type\":\"file\"")
                .contains("\"file_name\":\"v.mp4\"");

    }

    @Test
    void audioAndDocumentsBecomeFiles(@TempDir Path dir) throws Exception {

        adapter("https://bot.example").execute(sendMedia(
                attachment(dir, MediaKind.AUDIO, "a.mp3", 10, "https://bot.example/media/a")));

        assertThat(bodies.get(0))
                .contains("\"type\":\"file\"")
                .contains("\"document_type\":\"mp3\"");

    }

    @Test
    void withoutAPublicUrlTheSourceLinkIsSentAsText(@TempDir Path dir) throws Exception {

        adapter("https://bot.example").execute(sendMedia(
                attachment(dir, MediaKind.VIDEO, "v.mp4", 10, null)));

        assertThat(bodies.get(0))
                .contains("\"type\":\"text\"")
                .contains("https://source.example/page");

    }

    @Test
    void viberStatusErrorsAreClassified() {

        nextResponse = "{\"status\":201,\"status_message\":\"user not found\"}";

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        adapter("https://bot.example").execute(new OutboundMessage.Send(
                                Platform.VIBER, "u", RichText.plain("x"), InlineKeyboard.empty())))
                .isInstanceOf(eu.neydev.saver.core.api.PlatformException
                        .PermanentDeliveryException.class);

    }

}
