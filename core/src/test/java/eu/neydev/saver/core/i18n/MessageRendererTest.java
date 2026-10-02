package eu.neydev.saver.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRendererTest {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final Locale EN = Locale.forLanguageTag("en");

    private MessageRenderer renderer() {
        MessageBundle bundle = MessageBundle.load("messages", null, Set.of("ru", "en"));
        return new MessageRenderer(bundle, "ru");
    }

    @Test
    void substitutesPlaceholders() {
        String text = renderer().raw("message.countdown", RU,
                Map.of("days", 5, "date", "5 марта"));
        assertThat(text).isEqualTo("Осталось 5 дней до 5 марта");
    }

    @Test
    void russianPluralForms() {

        MessageRenderer renderer = renderer();
        assertThat(renderer.raw("message.countdown", RU, Map.of("days", 1, "date", "X")))
                .contains("1 день");
        assertThat(renderer.raw("message.countdown", RU, Map.of("days", 3, "date", "X")))
                .contains("3 дня");
        assertThat(renderer.raw("message.countdown", RU, Map.of("days", 25, "date", "X")))
                .contains("25 дней");

    }

    @Test
    void englishPluralForms() {

        MessageRenderer renderer = renderer();
        assertThat(renderer.raw("message.countdown", EN, Map.of("days", 1, "date", "X")))
                .contains("1 day");
        assertThat(renderer.raw("message.countdown", EN, Map.of("days", 2, "date", "X")))
                .contains("2 days");

    }

    @Test
    void fallsBackToDefaultLanguage() {
        String text = renderer().raw("message.countdown", Locale.forLanguageTag("fr"),
                Map.of("days", 2, "date", "X"));
        assertThat(text).startsWith("Осталось");
    }

    @Test
    void missingKeyDegradesVisibly() {
        assertThat(renderer().raw("message.nope", RU, Map.of())).isEqualTo("[missing:message.nope]");
    }

}

