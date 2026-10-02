package eu.neydev.saver.platform.vk;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.api.InlineKeyboard;

/**
 * Core keyboard -> VK inline keyboard. Actions are encoded into the payload
 * ({@code {"a":"<actionId>"}}) - the 255-byte payload limit is met with short ids.
 * Captions are truncated to 40 characters (the VK limit).
 */
public final class VkKeyboardMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VkKeyboardMapper() {
    }

    public static String map(InlineKeyboard keyboard) {

        if (keyboard.isEmpty()) {
            return "";
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("one_time", false);
        root.put("inline", true);
        ArrayNode rows = root.putArray("buttons");

        for (var row : keyboard.rows()) {

            ArrayNode rowNode = rows.addArray();

            for (var button : row) {

                ObjectNode buttonNode = rowNode.addObject();
                ObjectNode action = buttonNode.putObject("action");

                if (button.url() != null) {
                    action.put("type", "open_link");
                    action.put("link", button.url());
                } else {

                    action.put("type", "callback");
                    ObjectNode payload = MAPPER.createObjectNode();
                    payload.put("a", button.actionId());
                    action.set("payload", payload);

                }

                action.put("label", truncate(button.label(), 40));
                buttonNode.put("color", switch (button.style()) {
                    case PRIMARY -> "primary";
                    case DANGER -> "negative";
                    case SECONDARY -> "secondary";
                });

            }

        }

        return root.toString();

    }

    private static String truncate(String label, int max) {
        return label.length() <= max ? label : label.substring(0, max - 1) + "…";
    }

}

