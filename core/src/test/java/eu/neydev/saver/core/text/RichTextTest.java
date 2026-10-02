package eu.neydev.saver.core.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RichTextTest {

    @Test
    void parsesAllSegmentTypes() {

        RichText text = RichText.parse("hello *bold* _italic_ `code` [link](https://x.y)");
        var styled = text.segments().stream()
                .filter(segment -> !(segment instanceof RichText.Segment.Text))
                .toList();

        assertThat(styled).containsExactly(
                new RichText.Segment.Bold("bold"),
                new RichText.Segment.Italic("italic"),
                new RichText.Segment.Code("code"),
                new RichText.Segment.Link("link", "https://x.y"));

    }

    @Test
    void unclosedMarkersStayPlain() {
        RichText text = RichText.parse("asterisk * and all");
        assertThat(text.toPlainText()).isEqualTo("asterisk * and all");
    }

    @Test
    void backslashEscapesProduceLiterals() {

        RichText text = RichText.parse("star \\* not markup and \\[ not a link");
        assertThat(text.toPlainText()).isEqualTo("star * not markup and [ not a link");
        assertThat(text.segments()).allMatch(segment -> segment instanceof RichText.Segment.Text);

    }

    @Test
    void escapeValueNeutralizesMarkup() {

        String escaped = RichText.escapeValue("name *zv* [x](y)");
        assertThat(RichText.parse("hello " + escaped).toPlainText())
                .isEqualTo("hello name *zv* [x](y)");
        // an escaped value merges into a single text segment without markup
        assertThat(RichText.parse("hello " + escaped).segments()).hasSize(1);

    }

    @Test
    void plainTextRoundTrip() {
        RichText text = RichText.parse("a *b* c");
        assertThat(text.toPlainText()).isEqualTo("a b c");
    }


    @Test
    void truncateKeepsMarkupWholeAndCountsPlainText() {

        // A caption of bold+text: the limit counts TEXT, and the cut must never
        // slice a segment in a way that renders broken markup.
        RichText text = RichText.parse("*" + "T".repeat(2000) + "* tail");
        RichText cut = text.truncate(50);

        String plain = cut.toPlainText();

        assertThat(plain).hasSizeLessThanOrEqualTo(51);   // 50 + ellipsis
        assertThat(plain).endsWith("\u2026");

        // The bold segment survives as a WHOLE bold segment (renderer sees valid markup).
        assertThat(cut.segments().get(0)).isInstanceOf(RichText.Segment.Bold.class);

    }

    @Test
    void truncateIsANoOpForShortTexts() {

        RichText text = RichText.parse("short *bold*");

        assertThat(text.truncate(100)).isSameAs(text);

    }

}
