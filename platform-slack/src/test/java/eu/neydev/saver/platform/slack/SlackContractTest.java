package eu.neydev.saver.platform.slack;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The execute() contract: chat.postMessage with text and Block Kit blocks. */
class SlackContractTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/chat.postMessage", exchange -> {

            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ok\":true,\"ts\":\"1.2\"}".getBytes(StandardCharsets.UTF_8);
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
    void sendPostsTextAndBlocks() {

        SlackAdapter adapter = new SlackAdapter("xoxb-1", "xapp-1",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        adapter.execute(new OutboundMessage.Send(Platform.SLACK, "C123",
                RichText.parse("*bold*"),
                new InlineKeyboard(List.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("Date", "sd"))))));

        assertThat(bodies).hasSize(1);
        String body = java.net.URLDecoder.decode(bodies.get(0), StandardCharsets.UTF_8);
        assertThat(body).contains("channel=C123");
        assertThat(body).contains("blocks=");
        assertThat(body).contains("\"action_id\":\"sd\"");
        assertThat(body).contains("*bold*");

    }

}

