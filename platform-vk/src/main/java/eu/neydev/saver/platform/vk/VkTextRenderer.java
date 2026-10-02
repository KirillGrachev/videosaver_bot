package eu.neydev.saver.platform.vk;

import eu.neydev.saver.core.text.RichText;

/**
 * RichText -> VK text: VK has no markup in community messages,
 * so styling degrades to plain, and links expand into
 * "caption (url)" - clickable and without losing meaning.
 */
public final class VkTextRenderer {

    private VkTextRenderer() {
    }

    public static String render(RichText text) {

        StringBuilder sb = new StringBuilder();

        for (RichText.Segment segment : text.segments()) {
            switch (segment) {
                case RichText.Segment.Text t -> sb.append(t.value());
                case RichText.Segment.Bold b -> sb.append(b.value());
                case RichText.Segment.Italic i -> sb.append(i.value());
                case RichText.Segment.Code c -> sb.append(c.value());
                case RichText.Segment.Link l -> {
                    if (l.label().equals(l.url())) {
                        sb.append(l.url());
                    } else {
                        sb.append(l.label()).append(" (").append(l.url()).append(')');
                    }
                }
            }
        }

        return sb.toString();

    }

}

