package eu.neydev.saver.core.http;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Shared-secret authentication for webhook platforms that have no own
 * event signatures (Viber etc.): the secret is passed in the {@code s} query parameter
 * when registering the webhook AND is verified on inbound requests
 * (query {@code s} or the {@code x-webhook-secret} header).
 *
 * <p>Constant-time comparison, so as not to leak a timing oracle.
 */
public record WebhookSecret(@Nullable String value) {

    public boolean required() {
        return value != null && !value.isBlank();
    }

    public boolean matches(@NotNull WebhookHandler.WebhookRequest request) {

        if (!required()) {
            return true;
        }

        String fromQuery = queryParam(request.query(), "s");
        String fromHeader = request.headers().get("x-webhook-secret");

        return constantTimeEquals(value, fromQuery) || constantTimeEquals(value, fromHeader);

    }

    /** Webhook URL with the secret - for registration on the platform side. */
    public String decorateUrl(@NotNull String url) {
        return required() ? url + (url.contains("?") ? "&" : "?") + "s=" + value : url;
    }

    public static boolean constantTimeEquals(String expected, @Nullable String actual) {

        if (actual == null) {
            return false;
        }

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));

    }

    private static String queryParam(String query, String name) {

        for (String pair : query.split("&")) {

            int eq = pair.indexOf('=');

            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }

        }

        return null;

    }

}

