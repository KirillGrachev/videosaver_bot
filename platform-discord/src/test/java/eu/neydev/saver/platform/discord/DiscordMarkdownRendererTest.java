package eu.neydev.saver.platform.discord;

import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordMarkdownRendererTest {

    @Test
    void rendersNativeMarkdown() {
        String out = DiscordMarkdownRenderer.render(
                RichText.parse("text *bold* _it_ `code` [label](https://x.y)"));
        assertThat(out).isEqualTo("text **bold** *it* `code` [label](https://x.y)");
    }

    @Test
    void escapesSpecialCharactersInPlain() {
        assertThat(DiscordMarkdownRenderer.render(RichText.plain("a*b_c`d~e|f")))
                .isEqualTo("a\\*b\\_c\\`d\\~e\\|f");
    }

}

