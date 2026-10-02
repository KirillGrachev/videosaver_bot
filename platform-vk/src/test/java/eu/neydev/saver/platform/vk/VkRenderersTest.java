package eu.neydev.saver.platform.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VkRenderersTest {

    @Test
    void keyboardJsonStructure() throws Exception {

        InlineKeyboard keyboard = new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("Date", "sd",
                        InlineKeyboard.KeyboardButton.Style.PRIMARY))));
        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(keyboard));

        assertThat(json.path("inline").asBoolean()).isTrue();
        JsonNode button = json.path("buttons").path(0).path(0);
        assertThat(button.path("action").path("type").asText()).isEqualTo("callback");
        assertThat(button.path("action").path("payload").path("a").asText()).isEqualTo("sd");
        assertThat(button.path("color").asText()).isEqualTo("primary");

    }

    @Test
    void longLabelsTruncatedToVkLimit() throws Exception {

        InlineKeyboard keyboard = new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("x".repeat(80), "sd"))));
        JsonNode json = new ObjectMapper().readTree(VkKeyboardMapper.map(keyboard));
        assertThat(json.path("buttons").path(0).path(0).path("action").path("label").asText())
                .hasSize(40);

    }

    @Test
    void plainTextDegradesLinks() {
        String text = VkTextRenderer.render(RichText.parse("*bold* and [site](https://x.y)"));
        assertThat(text).isEqualTo("bold and site (https://x.y)");
    }

}

