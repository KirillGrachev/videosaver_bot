package eu.neydev.saver.platform.vk;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The VK media contract: photos travel through the photos upload server, videos through
 * video.save + a raw upload, everything else through docs - and the final messages.send
 * carries the exact attachment string VK expects ({@code type<owner>_<id>_<access>}).
 */
class VkMediaContractTest {

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    private String base;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/", exchange -> {

            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            calls.add(path);
            bodies.put(path, body);

            byte[] response = responses.getOrDefault(path, "{\"response\":1}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }

        });

        server.start();

        responses.put("/method/photos.getMessagesUploadServer",
                "{\"response\":{\"upload_url\":\"" + base + "/upload\"}}");
        responses.put("/upload", "{\"server\":\"7\",\"photo\":\"[]\",\"hash\":\"abc\"}");
        responses.put("/method/photos.saveMessagesPhoto",
                "{\"response\":[{\"id\":555,\"owner_id\":-666,\"access_key\":\"k1\"}]}");
        responses.put("/method/docs.getMessagesUploadServer",
                "{\"response\":{\"upload_url\":\"" + base + "/dupload\"}}");
        responses.put("/dupload", "{\"file\":\"FILETOKEN\"}");
        responses.put("/method/docs.save",
                "{\"response\":{\"type\":\"doc\",\"doc\":{\"id\":1,\"owner_id\":-2,\"access_key\":\"kk\"}}}");
        responses.put("/method/video.save",
                "{\"response\":{\"video_id\":9,\"owner_id\":-3,\"upload_url\":\""
                        + base + "/vupload\",\"access_key\":\"vk1\"}}");
        responses.put("/vupload", "{\"size\":123}");
        responses.put("/method/messages.send", "{\"response\":12345}");

    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private VkAdapter adapter() {
        return new VkAdapter("token", "1", base + "/method/");
    }

    private static MediaAttachment attachment(Path dir, MediaKind kind, String name)
            throws Exception {

        Path file = dir.resolve(name);
        Files.write(file, "content-bytes".getBytes(StandardCharsets.UTF_8));

        return MediaAttachment.of(kind, file, "Test Title", 1280, 720, 90,
                "https://source.example/page");

    }

    private static OutboundMessage.SendMedia sendMedia(MediaAttachment media) {
        return new OutboundMessage.SendMedia(Platform.VK, "100",
                RichText.plain("the caption"), InlineKeyboard.empty(), media);
    }

    @Test
    void photosTravelThroughThePhotosUploadServer(@TempDir Path dir) throws Exception {

        SendResult result = adapter().execute(sendMedia(attachment(dir, MediaKind.PHOTO, "p.jpg")));

        assertThat(calls).contains(
                "/method/photos.getMessagesUploadServer",
                "/upload",
                "/method/photos.saveMessagesPhoto",
                "/method/messages.send");

        assertThat(bodies.get("/method/messages.send"))
                .contains("attachment=photo-666_555_k1")
                // form-encoded caption
                .contains("message=the+caption");

        assertThat(result.messageId()).isEqualTo("m12345");

    }

    @Test
    void videosTravelThroughVideoSaveAndARawUpload(@TempDir Path dir) throws Exception {

        adapter().execute(sendMedia(attachment(dir, MediaKind.VIDEO, "v.mp4")));

        assertThat(calls).contains("/method/video.save", "/vupload");

        // The video server wants raw bytes, not a multipart form.
        assertThat(bodies.get("/vupload")).isEqualTo("content-bytes");
        assertThat(bodies.get("/method/messages.send"))
                .contains("attachment=video-3_9_vk1");

    }

    @Test
    void audioAndDocumentsTravelThroughDocs(@TempDir Path dir) throws Exception {

        adapter().execute(sendMedia(attachment(dir, MediaKind.AUDIO, "a.mp3")));

        assertThat(calls).contains("/method/docs.getMessagesUploadServer", "/dupload",
                "/method/docs.save");
        assertThat(bodies.get("/dupload")).contains("name=\"file\"");
        assertThat(bodies.get("/method/messages.send"))
                .contains("attachment=doc-2_1_kk");

    }

    @Test
    void anUnuploadableVideoDegradesToADocument(@TempDir Path dir) throws Exception {

        // video.save answers with a VK error: the adapter must fall back to docs
        // instead of failing the delivery.
        responses.put("/method/video.save",
                "{\"error\":{\"error_code\":29,\"error_msg\":\"Rate limit reached\"}}");

        adapter().execute(sendMedia(attachment(dir, MediaKind.VIDEO, "v.mp4")));

        assertThat(calls).contains("/method/docs.getMessagesUploadServer");
        assertThat(bodies.get("/method/messages.send"))
                .contains("attachment=doc-2_1_kk");

    }

    @Test
    void aVanishedFileIsAPermanentFailure(@TempDir Path dir) throws Exception {

        MediaAttachment media = attachment(dir, MediaKind.VIDEO, "gone.mp4");
        Files.delete(media.file());

        assertThat(calls).isEmpty();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter().execute(sendMedia(media)))
                .isInstanceOf(eu.neydev.saver.core.api.PlatformException
                        .PermanentDeliveryException.class);

    }

    @Test
    void ownMessageIdsRoundTripThroughEdit() throws Exception {

        // A Send answers with "m<id>"; an Edit of that id must use message_id,
        // while a callback id (no prefix) must stay conversation_message_id.
        VkAdapter adapter = adapter();

        SendResult sent = adapter.execute(new OutboundMessage.Send(Platform.VK, "100",
                RichText.plain("hi"), InlineKeyboard.empty()));
        assertThat(sent.messageId()).isEqualTo("m12345");

        adapter.execute(new OutboundMessage.Edit(Platform.VK, "100", sent.messageId(),
                RichText.plain("edited"), InlineKeyboard.empty()));
        assertThat(bodies.get("/method/messages.edit")).contains("message_id=12345");

        adapter.execute(new OutboundMessage.Edit(Platform.VK, "100", "777",
                RichText.plain("edited2"), InlineKeyboard.empty()));
        assertThat(bodies.get("/method/messages.edit")).contains("conversation_message_id=777");

    }

}
