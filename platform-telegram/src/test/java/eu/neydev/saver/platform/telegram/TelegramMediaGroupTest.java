package eu.neydev.saver.platform.telegram;

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
import org.telegram.telegrambots.meta.TelegramUrl;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The album and long-caption contract: sendMediaGroup goes to the native Telegram
 * method with every file in ONE request, and a 5000-character title is truncated on
 * the RichText level - the rendered HTML must never contain a half-cut tag.
 */
class TelegramMediaGroupTest {

    private static final String TOKEN = "123456:TEST";

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String response = """
            {"ok":true,"result":{"message_id":7,"date":1,"chat":{"id":100,"type":"private"}}}
            """;

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
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

    private static MediaAttachment photo(Path dir, String name) throws Exception {

        Path file = dir.resolve(name);
        Files.write(file, ("bytes-of-" + name).getBytes(StandardCharsets.UTF_8));

        return MediaAttachment.of(MediaKind.PHOTO, file, null, null, null, null, null);

    }

    @Test
    void albumsGoThroughSendMediaGroupInOneRequest(@TempDir Path dir) throws Exception {

        response = """
                {"ok":true,"result":[{"message_id":11,"date":1,"chat":{"id":100,"type":"private"}},
                                     {"message_id":12,"date":1,"chat":{"id":100,"type":"private"}}]}
                """;

        SendResult result = adapter().execute(new OutboundMessage.SendMediaGroup(
                Platform.TELEGRAM, "100", RichText.parse("*album*"), InlineKeyboard.empty(),
                List.of(photo(dir, "one.jpg"), photo(dir, "two.jpg")), false));

        assertThat(paths.get(0)).isEqualToIgnoringCase("/bot" + TOKEN + "/sendMediaGroup");

        String body = bodies.get(0);

        assertThat(body).contains("one.jpg").contains("two.jpg");
        assertThat(body).contains("bytes-of-one.jpg").contains("bytes-of-two.jpg");
        // the caption rides on the group as HTML on the first item
        assertThat(body).contains("<b>album</b>");

        assertThat(result.messageId()).isEqualTo("11");

    }

    @Test
    void longCaptionsAreCutOnTextNeverOnTags(@TempDir Path dir) throws Exception {

        String huge = "x".repeat(5000);
        MediaAttachment media = photo(dir, "p.jpg");

        adapter().execute(new OutboundMessage.SendMedia(Platform.TELEGRAM, "100",
                RichText.parse("*" + huge + "*"), InlineKeyboard.empty(), media));

        String body = bodies.get(0);

        // Exactly one well-formed bold pair, no dangling "<b>xxxx" without "</b>",
        // and the ellipsis marks the cut.
        assertThat(body).contains("<b>");
        assertThat(body).contains("</b>");
        assertThat(body).contains("\u2026");
        assertThat(countOf(body, "<b>")).isEqualTo(countOf(body, "</b>"));

        // The caption field itself stays within a sane bound (1024 text + tags + escaping).
        assertThat(body.length()).isLessThan(4000);

    }

    private static int countOf(String haystack, String needle) {

        int count = 0;
        int index = haystack.indexOf(needle);

        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }

        return count;

    }

}
