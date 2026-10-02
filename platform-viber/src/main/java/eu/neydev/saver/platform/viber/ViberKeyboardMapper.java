package eu.neydev.saver.platform.viber;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.saver.core.api.InlineKeyboard;

/**
 * Viber grid keyboard: 6 columns per row, the row's buttons split them evenly.
 * Callback buttons carry the actionId in tracking_data.
 */
public final class ViberKeyboardMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ROW_COLUMNS = 6;
    private static final int LABEL_MAX = 30;

    private ViberKeyboardMapper() {
    }

    public static ObjectNode map(InlineKeyboard keyboard) {

        ObjectNode root = MAPPER.createObjectNode();
        root.put("Type", "keyboard");
        root.put("DefaultHeight", false);

        var buttons = root.putArray("Buttons");

        if (keyboard.isEmpty()) {
            return root;
        }

        for (var row : keyboard.rows()) {

            int columns = Math.max(1, ROW_COLUMNS / Math.max(1, row.size()));

            for (var button : row) {

                ObjectNode node = buttons.addObject();

                if (button.url() != null) {
                    node.put("ActionType", "open-url");
                    node.put("ActionBody", button.url());
                } else {
                    node.put("ActionType", "reply");
                    node.put("ActionBody", button.actionId());
                    node.put("TrackingData", button.actionId());
                }

                node.put("Text", truncate(button.label(), LABEL_MAX));
                node.put("Columns", columns);
                node.put("Rows", 1);

            }

        }

        return root;

    }

    private static String truncate(String label, int max) {
        return label.length() <= max ? label : label.substring(0, max - 1) + "…";
    }

}

