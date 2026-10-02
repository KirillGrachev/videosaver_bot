package eu.neydev.saver.platform.telegram;

import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramRenderersTest {

    @Test
    void htmlEscapingAndStyles() {

        String html = TelegramHtmlRenderer.render(
                RichText.parse("a < b & c *bold* _it_ `code` [x](https://y.z/?a=1&b=2)"));
        assertThat(html).contains("a &lt; b &amp; c");
        assertThat(html).contains("<b>bold</b>");
        assertThat(html).contains("<i>it</i>");
        assertThat(html).contains("<code>code</code>");
        assertThat(html).contains("<a href=\"https://y.z/?a=1&amp;b=2\">x</a>");

    }

    @Test
    void keyboardMapping() {

        InlineKeyboard keyboard = new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("Date", "sd"),
                        InlineKeyboard.KeyboardButton.url("Site", "https://x.y"))));
        InlineKeyboardMarkup markup = TelegramKeyboardMapper.map(keyboard);
        assertThat(markup.getKeyboard()).hasSize(1);
        assertThat(markup.getKeyboard().get(0)).hasSize(2);
        assertThat(markup.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo("sd");
        assertThat(markup.getKeyboard().get(0).get(1).getUrl()).isEqualTo("https://x.y");

    }

    @Test
    void emptyKeyboardMapsToNull() {
        assertThat(TelegramKeyboardMapper.map(InlineKeyboard.empty())).isNull();
    }

}

