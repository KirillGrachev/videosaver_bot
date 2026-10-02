package eu.neydev.saver.platform.whatsapp;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.IncomingUpdate;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The execute() contract: Graph API messages with interactive buttons or text. */
class WhatsAppContractTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/v21.0/1234/messages", exchange -> {

            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"messages\":[{\"id\":\"wamid.1\"}]}".getBytes(StandardCharsets.UTF_8);
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
        return new WhatsAppAdapter("token", "1234:verify", "whsec",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v21.0/");
    }

    @Test
    void sendWithButtonsUsesInteractive() {

        adapter().execute(new OutboundMessage.Send(Platform.WHATSAPP, "7999",
                RichText.plain("hello"),
                new InlineKeyboard(List.of(
                        List.of(InlineKeyboard.KeyboardButton.callback("To", "dt")),
                        List.of(InlineKeyboard.KeyboardButton.callback("From", "ds"))))));

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"type\":\"interactive\"");
        assertThat(bodies.get(0)).contains("\"id\":\"dt\"");
        assertThat(bodies.get(0)).contains("\"to\":\"7999\"");

    }

    @Test
    void webhookVerifyAndSecretGuard() {

        WhatsAppAdapter adapter = adapter();
        List<IncomingUpdate> updates = new java.util.ArrayList<>();
        adapter.start(new eu.neydev.saver.core.api.PlatformContext(
                updates::add, new eu.neydev.saver.core.metrics.PlatformHealth()));

        var verify = adapter.handle(new eu.neydev.saver.core.http.WebhookHandler.WebhookRequest(
                "GET", "hub.mode=subscribe&hub.verify_token=verify&hub.challenge=C1", Map.of(), ""));
        assertThat(verify.status()).isEqualTo(200);
        assertThat(verify.body()).isEqualTo("C1");

        var denied = adapter.handle(new eu.neydev.saver.core.http.WebhookHandler.WebhookRequest(
                "POST", "", Map.of(), "{\"entry\":[]}"));
        assertThat(denied.status()).isEqualTo(401);

        var ok = adapter.handle(new eu.neydev.saver.core.http.WebhookHandler.WebhookRequest(
                "POST", "", Map.of("x-webhook-secret", "whsec"),
                """
                {"entry":[{"changes":[{"value":{"messages":[
                  {"from":"7999","id":"w1","text":{"body":"hi"}}]}}]}]}
                """));

        assertThat(ok.status()).isEqualTo(200);
        assertThat(updates).hasSize(1);

        var oversized = adapter.handle(new eu.neydev.saver.core.http.WebhookHandler.WebhookRequest(
                "POST", "", Map.of("x-webhook-secret", "whsec"), "x".repeat(600 * 1024)));
        assertThat(oversized.status()).isEqualTo(413);

    }

    @Test
    void editDegradesToSendAndDeleteIsNoop() {

        WhatsAppAdapter adapter = adapter();
        adapter.execute(new OutboundMessage.Edit(Platform.WHATSAPP, "7999", "w1",
                RichText.plain("edit"), InlineKeyboard.empty()));
        adapter.execute(new OutboundMessage.Delete(Platform.WHATSAPP, "7999", "w1"));
        adapter.execute(new OutboundMessage.AnswerCallback(Platform.WHATSAPP, "7999", "i", "toast", true));

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("edit");

    }

    @Test
    void moreThanThreeButtonsDegradeToText() {

        adapter().execute(new OutboundMessage.Send(Platform.WHATSAPP, "7999",
                RichText.plain("hello"),
                new InlineKeyboard(List.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("a", "a"),
                        InlineKeyboard.KeyboardButton.callback("b", "b"),
                        InlineKeyboard.KeyboardButton.callback("c", "c"),
                        InlineKeyboard.KeyboardButton.callback("d", "d"))))));

        assertThat(bodies.get(0)).contains("\"type\":\"text\"");

    }

}

