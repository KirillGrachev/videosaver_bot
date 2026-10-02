package eu.neydev.saver.platform.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppEventMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsTextAndButtonReply() throws Exception {

        var text = MAPPER.readTree("""
                {"from":"7999","id":"w1","text":{"body":"/start"}}
                """);
        var mapped = WhatsAppEventMapper.mapMessage(text);
        assertThat(mapped).isPresent();
        assertThat(mapped.get().user().platform()).isEqualTo(Platform.WHATSAPP);
        assertThat(mapped.get().chatId()).isEqualTo("7999");

        var button = MAPPER.readTree("""
                {"from":"7999","id":"w2","interactive":{"button_reply":{"id":"dt","title":"x"}}}
                """);
        var callback = (IncomingUpdate.Callback) WhatsAppEventMapper.mapMessage(button).orElseThrow();
        assertThat(callback.actionId()).isEqualTo("dt");

    }

    @Test
    void payloadWithButtonsAndDegradation() {

        var withButtons = WhatsAppPayloadBuilder.buildSend("7999", RichText.plain("text"),
                InlineKeyboard.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("a", "a"),
                        InlineKeyboard.KeyboardButton.callback("b", "b"))));
        assertThat(withButtons.path("type").asText()).isEqualTo("interactive");

        var many = WhatsAppPayloadBuilder.buildSend("7999", RichText.plain("text"),
                InlineKeyboard.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("a", "a"),
                        InlineKeyboard.KeyboardButton.callback("b", "b"),
                        InlineKeyboard.KeyboardButton.callback("c", "c"),
                        InlineKeyboard.KeyboardButton.callback("d", "d"))));
        assertThat(many.path("type").asText()).isEqualTo("text");

    }

}

