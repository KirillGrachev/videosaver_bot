package eu.neydev.saver.platform.viber;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;

import java.util.Optional;

/**
 * Mapping Viber webhook events: regular messages, button presses
 * (tracking_data = actionId) and dialog start. A pure function of JSON.
 */
public final class ViberEventMapper {

    private ViberEventMapper() {
    }

    public static Optional<IncomingUpdate> mapEvent(JsonNode event) {

        String type = event.path("event").asText("");

        return switch (type) {
            case "message" -> mapMessage(event);
            case "conversation_started" -> mapConversationStarted(event);
            default -> Optional.empty();
        };

    }

    private static Optional<IncomingUpdate> mapMessage(JsonNode event) {

        JsonNode message = event.path("message");
        String senderId = event.path("sender").path("id").asText("");

        if (senderId.isEmpty()) {
            return Optional.empty();
        }

        String tracking = message.path("tracking_data").asText("");

        if (!tracking.isBlank()) {
            return Optional.of(new IncomingUpdate.Callback(
                    user(senderId),
                    senderId,
                    tracking,
                    String.valueOf(message.path("message_token").asLong()),
                    senderId,
                    null));
        }

        String text = message.path("text").asText("");

        if (text.isBlank() || message.path("is_system").asBoolean(false)) {
            return Optional.empty();
        }

        return Optional.of(new IncomingUpdate.TextMessage(user(senderId), senderId, text, null));

    }

    private static Optional<IncomingUpdate> mapConversationStarted(JsonNode event) {

        String userId = event.path("user").path("id").asText("");

        if (userId.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new IncomingUpdate.TextMessage(user(userId), userId, "/start", null));

    }

    private static PlatformUser user(String id) {
        return new PlatformUser(Platform.VIBER, id);
    }

}

