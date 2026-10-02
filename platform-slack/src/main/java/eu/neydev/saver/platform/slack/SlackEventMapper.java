package eu.neydev.saver.platform.slack;

import com.fasterxml.jackson.databind.JsonNode;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;

import java.util.Optional;

/**
 * Mapping Slack envelopes (events_api, interactive) into normalized core events.
 * A pure function of JSON: knows nothing of transport or network.
 */
public final class SlackEventMapper {

    private SlackEventMapper() {
    }

    public static Optional<IncomingUpdate> mapEvent(JsonNode event) {

        if (!"message".equals(event.path("type").asText()) || event.has("bot_id")) {
            return Optional.empty();
        }

        String text = event.path("text").asText("");

        if (text.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new IncomingUpdate.TextMessage(
                user(event.path("user").asText()),
                event.path("channel").asText(),
                text,
                null));

    }

    public static Optional<IncomingUpdate> mapInteractive(JsonNode payload) {

        JsonNode action = payload.path("actions").path(0);
        String actionId = action.path("action_id").asText(null);

        if (actionId == null) {
            return Optional.empty();
        }

        String channel = payload.path("channel").path("id").asText();
        String ts = payload.path("message").path("ts").asText("0");

        return Optional.of(new IncomingUpdate.Callback(
                user(payload.path("user").path("id").asText()),
                channel,
                actionId,
                channel + "|" + ts,
                payload.path("user").path("id").asText(),
                null));

    }

    private static PlatformUser user(String id) {
        return new PlatformUser(Platform.SLACK, id);
    }

}

