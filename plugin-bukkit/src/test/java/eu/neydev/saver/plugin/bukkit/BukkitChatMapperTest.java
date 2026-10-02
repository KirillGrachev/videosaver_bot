package eu.neydev.saver.plugin.bukkit;

import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat convention is the plugin's whole user interface - every branch of it is
 * pinned here: commands, aliases, explicit slashes, button presses, bare URLs and
 * everything the bot must NOT touch (unprefixed player chat).
 */
class BukkitChatMapperTest {

    private static final String PREFIX = "!";

    private static Optional<BukkitChatMapper.Parsed> parse(String raw) {
        return BukkitChatMapper.parse(PREFIX, raw);
    }

    @Test
    void catalogCommandIsRewrittenToSlashForm() {

        assertThat(parse("!start"))
                .contains(new BukkitChatMapper.Parsed.Command("/start"));
        assertThat(parse("!help"))
                .contains(new BukkitChatMapper.Parsed.Command("/help"));

    }

    @Test
    void commandWithArgumentKeepsTheArgument() {

        assertThat(parse("!save https://youtu.be/dQw4w9WgXcQ"))
                .contains(new BukkitChatMapper.Parsed.Command("/save https://youtu.be/dQw4w9WgXcQ"));

    }

    @Test
    void catalogAliasesWork() {

        assertThat(parse("!dl https://tiktok.com/x"))
                .contains(new BukkitChatMapper.Parsed.Command("/dl https://tiktok.com/x"));
        assertThat(parse("!q"))
                .contains(new BukkitChatMapper.Parsed.Command("/q"));

    }

    @Test
    void commandNamesAreCaseInsensitive() {

        assertThat(parse("!HELP"))
                .contains(new BukkitChatMapper.Parsed.Command("/HELP"));

    }

    @Test
    void explicitSlashFormPassesThrough() {

        assertThat(parse("!/start"))
                .contains(new BukkitChatMapper.Parsed.Command("/start"));

    }

    @Test
    void callbackPressIsParsed() {

        assertThat(parse("!cb quality_720"))
                .contains(new BukkitChatMapper.Parsed.CallbackPress("quality_720"));

    }

    @Test
    void malformedCallbackPressesAreIgnored() {

        assertThat(parse("!cb")).isEmpty();
        assertThat(parse("!cb two words")).isEmpty();

    }

    @Test
    void bareUrlAfterPrefixIsFreeText() {

        assertThat(parse("!https://youtu.be/dQw4w9WgXcQ"))
                .contains(new BukkitChatMapper.Parsed.FreeText("https://youtu.be/dQw4w9WgXcQ"));

    }

    @Test
    void unprefixedChatIsNeverTouched() {

        assertThat(parse("hello world")).isEmpty();
        assertThat(parse("https://youtu.be/x look at this")).isEmpty();
        assertThat(parse((String) null)).isEmpty();

    }

    @Test
    void prefixAloneIsIgnored() {

        assertThat(parse("!")).isEmpty();
        assertThat(parse("!   ")).isEmpty();

    }

    @Test
    void customPrefixIsHonored() {

        assertThat(BukkitChatMapper.parse("#", "#save https://x"))
                .contains(new BukkitChatMapper.Parsed.Command("/save https://x"));
        assertThat(BukkitChatMapper.parse("#", "!save https://x")).isEmpty();

    }

    @Test
    void renderIsPlainTextWithoutKeyboard() {

        String rendered = BukkitChatMapper.render(PREFIX,
                RichText.parse("Done: *file*"), InlineKeyboard.empty());

        assertThat(rendered).isEqualTo("Done: file");

    }

    @Test
    void renderTurnsButtonsIntoHintLines() {

        InlineKeyboard keyboard = InlineKeyboard.of(List.of(
                InlineKeyboard.KeyboardButton.callback("720p", "quality_720"),
                InlineKeyboard.KeyboardButton.url("Source", "https://youtu.be/x")));

        String rendered = BukkitChatMapper.render(PREFIX, RichText.plain("Pick:"), keyboard);

        assertThat(rendered).isEqualTo("Pick:\n> 720p - !cb quality_720\n> Source: https://youtu.be/x");

    }

}
