package eu.neydev.saver.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;

import java.util.Optional;

/**
 * Mapping WhatsApp webhook events into core events: text messages
 * and interactive button presses. A pure function of JSON.
 */
public final class WhatsAppEventMapper {

    private WhatsAppEventMapper() {
    }

    public static Optional<IncomingUpdate> mapMessage(JsonNode message) {

        String from = message.path("from").asText("");

        if (from.isEmpty()) {
            return Optional.empty();
        }

        if (message.has("text")) {
            return Optional.of(new IncomingUpdate.TextMessage(
                    user(from),
                    from,
                    message.path("text").path("body").asText(""),
                    null));
        }

        if (message.has("interactive")) {

            JsonNode reply = message.path("interactive").path("button_reply");
            String actionId = reply.path("id").asText(null);

            if (actionId == null) {
                return Optional.empty();
            }

            return Optional.of(new IncomingUpdate.Callback(
                    user(from),
                    from,
                    actionId,
                    message.path("id").asText("0"),
                    from,
                    null));

        }

        return Optional.empty();

    }

    private static PlatformUser user(String phone) {
        return new PlatformUser(Platform.WHATSAPP, phone);
    }

}

