package eu.neydev.saver.platform.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.text.RichText;

/**
 * Rendering core structures into Slack formats: mrkdwn text and Block Kit blocks
 * (each core keyboard row = one actions block, up to 5 buttons).
 */
public final class SlackRenderers {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SlackRenderers() {
    }

    public static String mrkdwn(RichText text) {

        StringBuilder sb = new StringBuilder();

        for (RichText.Segment segment : text.segments()) {
            switch (segment) {
                case RichText.Segment.Text t -> sb.append(escape(t.value()));
                case RichText.Segment.Bold b -> sb.append('*').append(b.value()).append('*');
                case RichText.Segment.Italic i -> sb.append('_').append(i.value()).append('_');
                case RichText.Segment.Code c -> sb.append('`').append(c.value()).append('`');
                case RichText.Segment.Link l -> sb.append('<').append(l.url())
                        .append('|').append(l.label()).append('>');
            }
        }

        return sb.toString();

    }

    public static ArrayNode blocks(InlineKeyboard keyboard) {

        ArrayNode blocks = MAPPER.createArrayNode();

        if (keyboard.isEmpty()) {
            return blocks;
        }

        for (var row : keyboard.rows()) {

            ObjectNode block = blocks.addObject();
            block.put("type", "actions");
            ArrayNode elements = block.putArray("elements");

            for (var button : row) {

                ObjectNode element = elements.addObject();
                element.put("type", "button");
                element.put("text", button.label());

                if (button.url() != null) {
                    element.put("url", button.url());
                } else {
                    element.put("action_id", button.actionId());
                    element.put("value", button.actionId());
                }

                element.put("style", switch (button.style()) {
                    case PRIMARY -> "primary";
                    case DANGER -> "danger";
                    case SECONDARY -> "";
                });

            }

        }

        return blocks;

    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}

