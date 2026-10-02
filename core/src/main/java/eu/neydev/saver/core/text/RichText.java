package eu.neydev.saver.core.text;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Message mini-markup: a single intermediate format between YAML templates
 * and platform formats (Telegram HTML, Discord Markdown, VK plain).
 *
 * <p>Supported: {@code *bold*}, {@code _italic_}, {@code `code`},
 * {@code [text](https://url)}. Markup characters are escaped with a backslash
 * ({@code \*} -> a literal "*") - substituted user values
 * cannot inject markup. Nesting is deliberately not supported.
 *
 * <p>The parser is linear, with no regex allocations on the markup hot path.
 */
public record RichText(@NotNull List<Segment> segments) {

    public RichText {
        segments = List.copyOf(segments);
    }

    public static RichText plain(String text) {
        return new RichText(List.of(new Segment.Text(text)));
    }

    public static RichText parse(String markup) {

        List<Segment> out = new ArrayList<>(4);
        StringBuilder text = new StringBuilder();

        int i = 0;
        int n = markup.length();

        while (i < n) {

            char c = markup.charAt(i);

            if (c == '\\' && i + 1 < n) {

                text.append(markup.charAt(i + 1));
                i += 2;

                continue;

            }

            if (c == '[' ) {

                int close = markup.indexOf(']', i + 1);
                int paren = close + 1 < n && markup.charAt(close + 1) == '(' ? close + 1 : -1;
                int parenClose = paren >= 0 ? markup.indexOf(')', paren + 1) : -1;

                if (close > i && paren == close + 1 && parenClose > paren) {

                    flushText(out, text);
                    out.add(new Segment.Link(markup.substring(i + 1, close),
                            markup.substring(paren + 1, parenClose)));
                    i = parenClose + 1;

                    continue;

                }

            }

            if (c == '*' || c == '_' || c == '`') {

                int close = markup.indexOf(c, i + 1);

                if (close > i + 1) {

                    flushText(out, text);

                    String inner = markup.substring(i + 1, close);
                    out.add(switch (c) {
                        case '*' -> new Segment.Bold(inner);
                        case '_' -> new Segment.Italic(inner);
                        default -> new Segment.Code(inner);
                    });

                    i = close + 1;
                    continue;

                }

            }

            text.append(c);
            i++;

        }

        flushText(out, text);
        return new RichText(out);

    }

    private static void flushText(List<Segment> out, StringBuilder buffer) {
        if (buffer.length() > 0) {
            out.add(new Segment.Text(buffer.toString()));
            buffer.setLength(0);
        }
    }

    /**
     * Truncates the TEXT content to {@code maxChars} plain characters, cutting whole
     * segments where possible and never producing broken markup: platform caption
     * limits (Telegram: 1024) count text, not tags, so truncating the rendered HTML
     * would both miscount and risk cutting a tag in half. A trailing ellipsis marks
     * the cut.
     */
    public RichText truncate(int maxChars) {

        int plain = segments.stream().mapToInt(RichText::plainOf).sum();

        if (plain <= maxChars) {
            return this;
        }

        List<Segment> out = new ArrayList<>(segments.size());
        int budget = Math.max(1, maxChars - 1);   // room for the ellipsis

        for (Segment segment : segments) {

            if (budget <= 0) {
                break;
            }

            String text = plainText(segment);

            if (text.length() <= budget) {

                out.add(segment);
                budget -= text.length();
                continue;

            }

            String cut = text.substring(0, budget);
            out.add(switch (segment) {
                case Segment.Bold ignored -> new Segment.Bold(cut);
                case Segment.Italic ignored -> new Segment.Italic(cut);
                case Segment.Code ignored -> new Segment.Code(cut);
                case Segment.Link link -> new Segment.Link(cut, link.url());
                case Segment.Text ignored -> new Segment.Text(cut);
            });
            budget = 0;

        }

        out.add(new Segment.Text("\u2026"));

        return new RichText(out);

    }

    private static int plainOf(Segment segment) {
        return plainText(segment).length();
    }

    private static String plainText(Segment segment) {

        return switch (segment) {
            case Segment.Text t -> t.value();
            case Segment.Bold b -> b.value();
            case Segment.Italic i -> i.value();
            case Segment.Code c -> c.value();
            case Segment.Link l -> l.label();
        };

    }

    /**
     * Escaping markup characters in user-provided values:
     * after substitution the parser sees them as literals.
     */
    public static String escapeValue(String value) {

        StringBuilder sb = new StringBuilder(value.length() + 8);

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            if (c == '*' || c == '_' || c == '`' || c == '[' || c == ']' || c == '\\') {
                sb.append('\\');
            }

            sb.append(c);

        }

        return sb.toString();

    }

    /** Plain-text representation (for logs and platforms without markup). */
    public String toPlainText() {

        StringBuilder sb = new StringBuilder();

        for (Segment segment : segments) {

            sb.append(switch (segment) {
                case Segment.Text t -> t.value();
                case Segment.Bold b -> b.value();
                case Segment.Italic it -> it.value();
                case Segment.Code c -> c.value();
                case Segment.Link l -> l.label();
            });

        }

        return sb.toString();

    }

    public sealed interface Segment {

        record Text(String value) implements Segment {}

        record Bold(String value) implements Segment {}

        record Italic(String value) implements Segment {}

        record Code(String value) implements Segment {}

        record Link(String label, String url) implements Segment {}

    }

}

