package eu.neydev.saver.core.http;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * The platform webhook handler contract. The bot's built-in HTTP server serves
 * not only the Mini App and metrics, but also accepts platform events that need
 * an inbound HTTPS endpoint (WhatsApp, Viber, ...): a separate site is not needed.
 */
public interface WebhookHandler {

    /** Mount path, for example {@code /hooks/whatsapp}. */
    String path();

    @NotNull WebhookResponse handle(@NotNull WebhookRequest request);

    /**
     * @param method  HTTP method;
     * @param query   raw query string (may be empty);
     * @param headers headers (names in lower case);
     * @param body    request body.
     */
    record WebhookRequest(@NotNull String method,
                          @NotNull String query,
                          @NotNull Map<String, String> headers,
                          @NotNull String body) {
    }

    record WebhookResponse(int status, @NotNull String contentType, @NotNull String body) {

        public static WebhookResponse ok(String body) {
            return new WebhookResponse(200, "application/json", body);
        }

        public static WebhookResponse plain(String body) {
            return new WebhookResponse(200, "text/plain", body);
        }

        public static WebhookResponse denied(String body) {
            return new WebhookResponse(401, "application/json", body);
        }

    }

}

