package eu.neydev.saver.platform.discord;

import eu.neydev.saver.core.api.InlineKeyboard;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping keyboards into Discord components (no gateway - a pure function). */
class DiscordComponentsTest {

    @Test
    void mapsCallbacksAndUrlsWithStyles() {

        List<ActionRow> rows = DiscordAdapter.components(new InlineKeyboard(List.of(
                List.of(InlineKeyboard.KeyboardButton.callback("Date", "sd",
                                InlineKeyboard.KeyboardButton.Style.PRIMARY),
                        InlineKeyboard.KeyboardButton.callback("Delete", "dl",
                                InlineKeyboard.KeyboardButton.Style.DANGER),
                        InlineKeyboard.KeyboardButton.url("Git", "https://g.h")))));

        assertThat(rows).hasSize(1);
        var components = rows.get(0).getComponents();

        assertThat(components).hasSize(3);
        var first = (net.dv8tion.jda.api.components.buttons.Button) components.get(0);
        var link = (net.dv8tion.jda.api.components.buttons.Button) components.get(2);

        assertThat(first.getCustomId()).isEqualTo("sd");
        assertThat(first.getStyle()).isEqualTo(net.dv8tion.jda.api.components.buttons.ButtonStyle.PRIMARY);
        assertThat(link.getUrl()).isEqualTo("https://g.h");

    }

    @Test
    void emptyKeyboardMapsToNoRows() {
        assertThat(DiscordAdapter.components(InlineKeyboard.empty())).isEmpty();
    }

}

