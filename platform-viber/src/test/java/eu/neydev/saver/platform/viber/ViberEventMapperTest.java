package eu.neydev.saver.platform.viber;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.Platform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ViberEventMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsMessageButtonAndConversationStart() throws Exception {

        var message = MAPPER.readTree("""
                {"event":"message","sender":{"id":"u1"},"message":{"text":"hello"}}
                """);

        assertThat(ViberEventMapper.mapEvent(message)).isPresent();

        var button = MAPPER.readTree("""
                {"event":"message","sender":{"id":"u1"},
                 "message":{"text":"Date","tracking_data":"sd","message_token":7}}
                """);

        var callback = (IncomingUpdate.Callback) ViberEventMapper.mapEvent(button).orElseThrow();
        assertThat(callback.actionId()).isEqualTo("sd");
        assertThat(callback.user().platform()).isEqualTo(Platform.VIBER);

        var started = MAPPER.readTree("""
                {"event":"conversation_started","user":{"id":"u2"}}
                """);

        var start = (IncomingUpdate.TextMessage) ViberEventMapper.mapEvent(started).orElseThrow();
        assertThat(start.text()).isEqualTo("/start");

    }

    @Test
    void keyboardGridColumns() {

        var keyboard = ViberKeyboardMapper.map(InlineKeyboard.of(List.of(
                InlineKeyboard.KeyboardButton.callback("a", "a"),
                InlineKeyboard.KeyboardButton.callback("b", "b"),
                InlineKeyboard.KeyboardButton.url("c", "https://x.y"))));
        var buttons = keyboard.path("Buttons");

        assertThat(buttons).hasSize(3);
        assertThat(buttons.path(0).path("Columns").asInt()).isEqualTo(2);
        assertThat(buttons.path(2).path("ActionType").asText()).isEqualTo("open-url");

    }

}

