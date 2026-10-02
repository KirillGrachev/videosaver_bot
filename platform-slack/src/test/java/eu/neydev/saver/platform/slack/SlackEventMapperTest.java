package eu.neydev.saver.platform.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.Platform;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackEventMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsUserMessageAndSkipsBots() throws Exception {

        var message = MAPPER.readTree("""
                {"type":"message","text":"hello","user":"U1","channel":"C1"}
                """);

        var mapped = SlackEventMapper.mapEvent(message);
        assertThat(mapped).isPresent();
        assertThat(mapped.get().user().platform()).isEqualTo(Platform.SLACK);
        assertThat(mapped.get().chatId()).isEqualTo("C1");

        var bot = MAPPER.readTree("""
                {"type":"message","text":"hello","bot_id":"B1","channel":"C1"}
                """);

        assertThat(SlackEventMapper.mapEvent(bot)).isEmpty();

    }

    @Test
    void mapsButtonInteraction() throws Exception {

        var payload = MAPPER.readTree("""
                {"user":{"id":"U1"},"channel":{"id":"C1"},
                 "message":{"ts":"1.2"},
                 "actions":[{"action_id":"sd","value":"sd"}]}
                """);

        var mapped = SlackEventMapper.mapInteractive(payload);

        assertThat(mapped).isPresent();
        assertThat(((IncomingUpdate.Callback) mapped.get()).actionId()).isEqualTo("sd");
        assertThat(mapped.get().chatId()).isEqualTo("C1");

    }

    @Test
    void mrkdwnAndBlocks() {

        assertThat(SlackRenderers.mrkdwn(eu.neydev.saver.core.text.RichText
                .parse("*b* _i_ `c` [t](https://u) & <")))
                .isEqualTo("*b* _i_ `c` <https://u|t> &amp; &lt;");

        var blocks = SlackRenderers.blocks(eu.neydev.saver.core.api.InlineKeyboard.of(
                java.util.List.of(eu.neydev.saver.core.api.InlineKeyboard.KeyboardButton
                        .callback("Date", "sd"))));

        assertThat(blocks.get(0).path("elements").path(0).path("action_id").asText()).isEqualTo("sd");

    }

}

