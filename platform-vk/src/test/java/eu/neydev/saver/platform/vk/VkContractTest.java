package eu.neydev.saver.platform.vk;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The execute() contract: what exactly goes to the VK API (body, keyboard, errors). */
class VkContractTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile String nextResponse = "{\"response\":1}";

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            paths.add(exchange.getRequestURI().getPath());
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

    private VkAdapter adapter() {
        return new VkAdapter("token", "1", "http://127.0.0.1:" + server.getAddress().getPort() + "/method/");
    }

    @Test
    void sendPostsTextAndKeyboard() {

        adapter().execute(new OutboundMessage.Send(Platform.VK, "100",
                RichText.plain("hello"),
                new InlineKeyboard(List.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("Date", "sd"))))));

        assertThat(bodies).hasSize(1);
        String body = bodies.get(0);

        assertThat(body).contains("peer_id=100");
        assertThat(body).contains("random_id=");
        assertThat(body).contains("keyboard=");
        assertThat(body).contains("access_token=token");

    }

    @Test
    void floodControlMapsToRateLimited() {
        nextResponse = "{\"error\":{\"error_code\":6,\"error_msg\":\"Too many requests\"}}";
        assertThatThrownBy(() -> adapter().execute(
                new OutboundMessage.Send(Platform.VK, "100", RichText.plain("x"), InlineKeyboard.empty())))
                .isInstanceOf(PlatformException.RateLimitedException.class);
    }

    @Test
    void permissionErrorKeepsTheVkCode() {

        nextResponse = "{\"error\":{\"error_code\":15,\"error_msg\":\"Access denied: no access "
                + "to call this method. It cannot be called with current scopes.\"}}";

        assertThatThrownBy(() -> adapter().execute(
                new OutboundMessage.Send(Platform.VK, "100", RichText.plain("x"), InlineKeyboard.empty())))
                .isInstanceOf(PlatformException.class)
                .isNotInstanceOf(PlatformException.RateLimitedException.class)
                .isNotInstanceOf(PlatformException.PermanentDeliveryException.class)
                .extracting(e -> ((PlatformException) e).errorCode())
                .isEqualTo(15);

        assertThat(paths).containsExactly("/method/messages.send");

    }

    @Test
    void privacyDenialMapsToPermanentDelivery() {
        nextResponse = "{\"error\":{\"error_code\":201,\"error_msg\":\"Access to the chat denied\"}}";
        assertThatThrownBy(() -> adapter().execute(
                new OutboundMessage.Send(Platform.VK, "100", RichText.plain("x"), InlineKeyboard.empty())))
                .isInstanceOf(PlatformException.PermanentDeliveryException.class);
    }

}

