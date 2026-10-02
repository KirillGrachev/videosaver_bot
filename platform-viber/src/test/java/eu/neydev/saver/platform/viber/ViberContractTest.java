package eu.neydev.saver.platform.viber;

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

/** The execute() contract: send_message with a grid keyboard. */
class ViberContractTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/pa/send_message", exchange -> {

            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":0,\"status_message\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
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
    void sendPostsKeyboardGrid() {

        ViberAdapter adapter = new ViberAdapter("token", "https://example.org", "sec",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/pa/");
        adapter.execute(new OutboundMessage.Send(Platform.VIBER, "user1",
                RichText.plain("hello"),
                new InlineKeyboard(List.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("Date", "sd"),
                        InlineKeyboard.KeyboardButton.callback("Days to", "dt"))))));

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"receiver\":\"user1\"");
        assertThat(bodies.get(0)).contains("\"TrackingData\":\"sd\"");
        assertThat(bodies.get(0)).contains("\"Columns\":3");

    }

}

