package eu.neydev.saver.platform.telegram;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramUrl;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The execute() contract: requests go to the given base URL in Bot API format. */
class TelegramContractTest {

    private static final String TOKEN = "123456:TEST";

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = """
                    {"ok":true,"result":{"message_id":7,"date":1,
                     "chat":{"id":100,"type":"private"}}}
                    """;
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

    private TelegramAdapter adapter() {
        return new TelegramAdapter(TOKEN, null, "Saver",
                TelegramUrl.builder()
                        .schema("http")
                        .host("127.0.0.1")
                        .port(server.getAddress().getPort())
                        .build());
    }

    @Test
    void sendGoesToBotApiWithHtmlAndKeyboard() {

        adapter().execute(new OutboundMessage.Send(Platform.TELEGRAM, "100",
                RichText.parse("*bold*"),
                new InlineKeyboard(List.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("Date", "sd"))))));

        assertThat(paths.get(0)).isEqualToIgnoringCase("/bot" + TOKEN + "/sendMessage");
        String body = bodies.get(0);
        assertThat(body).contains("\"chat_id\":\"100\"");
        assertThat(body).contains("\"parse_mode\":\"html\"");
        assertThat(body).contains("<b>");
        assertThat(body).contains("\"callback_data\":\"sd\"");

    }

    @Test
    void sendMediaUploadsTheFileThroughItsNativeMethod(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {

        java.nio.file.Path file = dir.resolve("clip.mp4");
        java.nio.file.Files.write(file, "telegram-bytes".getBytes(StandardCharsets.UTF_8));

        eu.neydev.saver.core.media.MediaAttachment media =
                eu.neydev.saver.core.media.MediaAttachment.of(
                        eu.neydev.saver.core.media.MediaKind.VIDEO, file,
                        "Clip Title", 1920, 1080, 90, null);

        var result = adapter().execute(new OutboundMessage.SendMedia(Platform.TELEGRAM, "100",
                RichText.parse("*clip*"), InlineKeyboard.empty(), media));

        assertThat(paths.get(0)).isEqualToIgnoringCase("/bot" + TOKEN + "/sendVideo");

        String body = bodies.get(0);

        // Multipart: the field names and the file bytes must be on the wire.
        assertThat(body).contains("name=\"chat_id\"").contains("100");
        assertThat(body).contains("name=\"video\"").contains("clip.mp4");
        assertThat(body).contains("telegram-bytes");
        assertThat(body).contains("<b>clip</b>");

        assertThat(result.messageId()).isEqualTo("7");

    }

    @Test
    void aVanishedMediaFileIsAPermanentFailure(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {

        java.nio.file.Path file = dir.resolve("gone.mp4");
        java.nio.file.Files.write(file, new byte[]{1});

        eu.neydev.saver.core.media.MediaAttachment media =
                eu.neydev.saver.core.media.MediaAttachment.of(
                        eu.neydev.saver.core.media.MediaKind.VIDEO, file, null,
                        null, null, null, null);

        java.nio.file.Files.delete(file);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter()
                        .execute(new OutboundMessage.SendMedia(Platform.TELEGRAM, "100",
                                RichText.plain("x"), InlineKeyboard.empty(), media)))
                .isInstanceOf(eu.neydev.saver.core.api.PlatformException
                        .PermanentDeliveryException.class);

    }

}

