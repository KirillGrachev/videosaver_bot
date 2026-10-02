package eu.neydev.saver.platform.telegram;

import eu.neydev.saver.core.text.RichText;

/**
 * RichText -> Telegram HTML (parse_mode=HTML).
 * Only {@code & < >} are escaped - the minimal reliable set for HTML mode;
 * bold/italic/code/links map to tags without nesting.
 */
public final class TelegramHtmlRenderer {

    private TelegramHtmlRenderer() {
    }

    public static String render(RichText text) {

        StringBuilder sb = new StringBuilder();

        for (RichText.Segment segment : text.segments()) {
            switch (segment) {
                case RichText.Segment.Text t -> sb.append(escape(t.value()));
                case RichText.Segment.Bold b -> sb.append("<b>").append(escape(b.value())).append("</b>");
                case RichText.Segment.Italic i -> sb.append("<i>").append(escape(i.value())).append("</i>");
                case RichText.Segment.Code c -> sb.append("<code>").append(escape(c.value())).append("</code>");
                case RichText.Segment.Link l -> sb.append("<a href=\"")
                        .append(escape(l.url())).append("\">")
                        .append(escape(l.label())).append("</a>");
            }
        }

        return sb.toString();

    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}

