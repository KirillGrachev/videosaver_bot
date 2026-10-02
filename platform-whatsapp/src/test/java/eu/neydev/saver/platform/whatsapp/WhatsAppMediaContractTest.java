package eu.neydev.saver.platform.whatsapp;

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
 * The WhatsApp media contract: a two-step delivery (multipart upload to {phoneId}/media,
 * then a messages payload referencing the media id) with the type mapping the Cloud API
 * expects - and the honest wamid passthrough for the job manager's edit flow.
 */
class WhatsAppMediaContractTest {

    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private String graphBase;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        graphBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        server.createContext("/", exchange -> {

            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            calls.add(path);
            bodies.put(path, body);

            String json = path.endsWith("/media")
                    ? "{\"id\":\"MEDIA-77\"}"
                    : "{\"messages\":[{\"id\":\"wamid.RESULT1\"}]}";

            byte[] response = json.getBytes(StandardCharsets.UTF_8);
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

    private WhatsAppAdapter adapter() {
        return new WhatsAppAdapter("token", "PHONE:verify", null, graphBase);
    }

    private static MediaAttachment attachment(Path dir, MediaKind kind, String name)
            throws Exception {

        Path file = dir.resolve(name);
        Files.write(file, "wa-bytes".getBytes(StandardCharsets.UTF_8));

        return MediaAttachment.of(kind, file, null, null, null, null, null);

    }

    private static OutboundMessage.SendMedia sendMedia(MediaAttachment media) {
        return new OutboundMessage.SendMedia(Platform.WHATSAPP, "79990000000",
                RichText.plain("hello caption"), InlineKeyboard.empty(), media);
    }

    @Test
    void theAdapterHonestlyDeclaresNoEditSupport() {
        // The job manager suppresses progress edits based on this flag - WhatsApp
        // turning an Edit into a new message is exactly the spam the flag prevents.
        assertThat(adapter().supportsEdits()).isFalse();
    }

    @Test
    void photosUploadThenReferenceTheMediaId(@TempDir Path dir) throws Exception {

        SendResult result = adapter().execute(sendMedia(attachment(dir, MediaKind.PHOTO, "p.jpg")));

        assertThat(calls).contains("/PHONE/media", "/PHONE/messages");
        assertThat(bodies.get("/PHONE/media")).contains("wa-bytes");

        String payload = bodies.get("/PHONE/messages");

        assertThat(payload)
                .contains("\"type\":\"image\"")
                .contains("\"id\":\"MEDIA-77\"")
                .contains("hello caption");

        assertThat(result.messageId()).isEqualTo("wamid.RESULT1");

    }

    @Test
    void videosAndDocumentsMapToTheirTypes(@TempDir Path dir) throws Exception {

        adapter().execute(sendMedia(attachment(dir, MediaKind.VIDEO, "v.mp4")));
        assertThat(bodies.get("/PHONE/messages")).contains("\"type\":\"video\"");

        adapter().execute(sendMedia(attachment(dir, MediaKind.DOCUMENT, "d.pdf")));
        String payload = bodies.get("/PHONE/messages");
        assertThat(payload).contains("\"type\":\"document\"")
                .contains("\"filename\":\"d.pdf\"");

    }

    @Test
    void realGifFilesBecomeDocuments(@TempDir Path dir) throws Exception {

        // WhatsApp has no GIF type: a .gif file is a document, an mp4 "gif" is a video.
        adapter().execute(sendMedia(attachment(dir, MediaKind.GIF, "fun.gif")));
        assertThat(bodies.get("/PHONE/messages")).contains("\"type\":\"document\"");

        adapter().execute(sendMedia(attachment(dir, MediaKind.GIF, "fun2.mp4")));
        assertThat(bodies.get("/PHONE/messages")).contains("\"type\":\"video\"");

    }

}
