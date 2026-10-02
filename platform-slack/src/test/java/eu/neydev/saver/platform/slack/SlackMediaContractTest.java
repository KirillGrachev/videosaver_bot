package eu.neydev.saver.platform.slack;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
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
 * The Slack upload contract: the modern three-step flow (getUploadURLExternal ->
 * raw PUT -> completeUploadExternal) with the caption riding along as initial_comment,
 * and message ids in the channel|ts shape the edit/delete paths expect.
 */
class SlackMediaContractTest {

    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private String apiBase;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

        server.createContext("/", exchange -> {

            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            calls.add(path);
            bodies.put(path, body);

            String json = switch (path) {

                case "/files.getUploadURLExternal" ->
                        "{\"ok\":true,\"upload_url\":\"" + apiBase + "upload\",\"file_id\":\"F77\"}";
                case "/files.completeUploadExternal" ->
                        "{\"ok\":true,\"files\":[{\"id\":\"F77\"}]}";
                default -> "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"123.456\"}";

            };

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

    @Test
    void mediaGoesThroughTheThreeStepUpload(@TempDir Path dir) throws Exception {

        Path file = dir.resolve("clip.mp4");
        Files.write(file, "slack-bytes".getBytes(StandardCharsets.UTF_8));

        MediaAttachment media = MediaAttachment.of(MediaKind.VIDEO, file, null,
                null, null, null, null);

        new SlackAdapter("xoxb-token", "xapp-token", apiBase)
                .execute(new OutboundMessage.SendMedia(Platform.SLACK, "C1",
                        RichText.plain("look at this"), InlineKeyboard.empty(), media));

        assertThat(calls).contains(
                "/files.getUploadURLExternal",
                "/upload",
                "/files.completeUploadExternal");

        // The raw PUT carries exactly the file bytes.
        assertThat(bodies.get("/upload")).isEqualTo("slack-bytes");

        String complete = bodies.get("/files.completeUploadExternal");

        assertThat(complete)
                .contains("F77")
                .contains("channel_id=C1")
                // form-encoded caption
                .contains("initial_comment=look+at+this");

    }

    @Test
    void albumsBecomeOneMessageWithEveryFile(@TempDir Path dir) throws Exception {

        Path one = dir.resolve("one.jpg");
        Path two = dir.resolve("two.jpg");
        Files.write(one, "aaa".getBytes(StandardCharsets.UTF_8));
        Files.write(two, "bbb".getBytes(StandardCharsets.UTF_8));

        var adapter = new SlackAdapter("xoxb-token", "xapp-token", apiBase);

        assertThat(adapter.supportsMediaGroups()).isTrue();

        adapter.execute(new OutboundMessage.SendMediaGroup(Platform.SLACK, "C1",
                RichText.plain("the album"), InlineKeyboard.empty(),
                List.of(
                        MediaAttachment.of(MediaKind.PHOTO, one, null, null, null, null, null),
                        MediaAttachment.of(MediaKind.PHOTO, two, null, null, null, null, null)),
                false));

        // Two uploads...
        assertThat(calls.stream().filter("/files.getUploadURLExternal"::equals).count())
                .isEqualTo(2);
        // ...but ONE completeUploadExternal sharing both files in one message.
        assertThat(calls.stream().filter("/files.completeUploadExternal"::equals).count())
                .isEqualTo(1);

        String complete = bodies.get("/files.completeUploadExternal");

        assertThat(complete).contains("F77");
        assertThat(complete).contains("the+album");
        // both file entries are in the files array (the fake answers F77 for both)
        assertThat(complete.split("%7B|\\{").length - 1).isGreaterThanOrEqualTo(2);

    }

    @Test
    void sendsReturnTheChannelTsMessageId() {

        var result = new SlackAdapter("xoxb-token", "xapp-token", apiBase)
                .execute(new OutboundMessage.Send(Platform.SLACK, "C1",
                        RichText.plain("hello"), InlineKeyboard.empty()));

        assertThat(result.messageId()).isEqualTo("C1|123.456");

    }

}
