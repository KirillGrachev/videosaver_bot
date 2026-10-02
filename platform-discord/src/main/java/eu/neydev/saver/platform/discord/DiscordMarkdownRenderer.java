package eu.neydev.saver.platform.discord;

import eu.neydev.saver.core.text.RichText;

/**
 * RichText -> Discord Markdown: native **bold**, *italic*, `code`, [label](url).
 * Service characters in plain text are escaped.
 */
public final class DiscordMarkdownRenderer {

    private DiscordMarkdownRenderer() {
    }

    public static String render(RichText text) {

        StringBuilder sb = new StringBuilder();

        for (RichText.Segment segment : text.segments()) {
            switch (segment) {
                case RichText.Segment.Text t -> sb.append(escape(t.value()));
                case RichText.Segment.Bold b -> sb.append("**").append(b.value()).append("**");
                case RichText.Segment.Italic i -> sb.append('*').append(i.value()).append('*');
                case RichText.Segment.Code c -> sb.append('`').append(c.value().replace("`", "'")).append('`');
                case RichText.Segment.Link l -> sb.append('[').append(l.label()).append("](")
                        .append(l.url()).append(')');
            }
        }

        return sb.toString();

    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("`", "\\`").replace("~", "\\~").replace("|", "\\|");
    }

}

