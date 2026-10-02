package eu.neydev.saver.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** The plural branch of every shipped language family, probed at its boundary values. */
class PluralRulesTest {

    private static PluralCategory of(String language, long n) {
        return PluralRules.of(Locale.forLanguageTag(language), n);
    }

    @Test
    void eastSlavic() {

        assertThat(of("ru", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("ru", 3)).isEqualTo(PluralCategory.FEW);
        assertThat(of("ru", 11)).isEqualTo(PluralCategory.MANY);
        assertThat(of("uk", 22)).isEqualTo(PluralCategory.FEW);
        assertThat(of("be", 25)).isEqualTo(PluralCategory.MANY);

    }

    @Test
    void westSlavic() {

        assertThat(of("pl", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("pl", 2)).isEqualTo(PluralCategory.FEW);
        assertThat(of("pl", 5)).isEqualTo(PluralCategory.MANY);
        assertThat(of("cs", 12)).isEqualTo(PluralCategory.MANY);
        assertThat(of("sk", 4)).isEqualTo(PluralCategory.FEW);

    }

    @Test
    void zeroOneLanguages() {

        assertThat(of("fr", 0)).isEqualTo(PluralCategory.ONE);
        assertThat(of("fr", 2)).isEqualTo(PluralCategory.OTHER);
        assertThat(of("pt", 1)).isEqualTo(PluralCategory.ONE);

    }

    @Test
    void baltic() {

        assertThat(of("lt", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("lt", 9)).isEqualTo(PluralCategory.FEW);
        assertThat(of("lt", 11)).isEqualTo(PluralCategory.OTHER);
        assertThat(of("lt", 21)).isEqualTo(PluralCategory.ONE);
        assertThat(of("lv", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("lv", 0)).isEqualTo(PluralCategory.MANY);
        assertThat(of("lv", 11)).isEqualTo(PluralCategory.MANY);
        assertThat(of("lv", 2)).isEqualTo(PluralCategory.OTHER);
        assertThat(of("lv", 21)).isEqualTo(PluralCategory.ONE);

    }

    @Test
    void romanian() {

        assertThat(of("ro", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("ro", 0)).isEqualTo(PluralCategory.FEW);
        assertThat(of("ro", 19)).isEqualTo(PluralCategory.FEW);
        assertThat(of("ro", 20)).isEqualTo(PluralCategory.OTHER);
        assertThat(of("ro", 101)).isEqualTo(PluralCategory.FEW);

    }

    @Test
    void southSlavic() {

        assertThat(of("hr", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("hr", 21)).isEqualTo(PluralCategory.ONE);
        assertThat(of("hr", 3)).isEqualTo(PluralCategory.FEW);
        assertThat(of("hr", 5)).isEqualTo(PluralCategory.OTHER);
        assertThat(of("sl", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("sl", 2)).isEqualTo(PluralCategory.TWO);
        assertThat(of("sl", 3)).isEqualTo(PluralCategory.FEW);
        assertThat(of("sl", 104)).isEqualTo(PluralCategory.FEW);
        assertThat(of("sl", 5)).isEqualTo(PluralCategory.OTHER);

    }

    @Test
    void irishAndMaltese() {

        assertThat(of("ga", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("ga", 2)).isEqualTo(PluralCategory.TWO);
        assertThat(of("ga", 6)).isEqualTo(PluralCategory.FEW);
        assertThat(of("ga", 7)).isEqualTo(PluralCategory.MANY);
        assertThat(of("ga", 11)).isEqualTo(PluralCategory.OTHER);
        assertThat(of("mt", 1)).isEqualTo(PluralCategory.ONE);
        assertThat(of("mt", 0)).isEqualTo(PluralCategory.FEW);
        assertThat(of("mt", 10)).isEqualTo(PluralCategory.FEW);
        assertThat(of("mt", 11)).isEqualTo(PluralCategory.MANY);
        assertThat(of("mt", 20)).isEqualTo(PluralCategory.OTHER);

    }

    @Test
    void simpleOneOther() {
        for (String language : new String[] {"en", "de", "es", "it", "nl", "sv", "da",
                "fi", "et", "el", "hu", "bg", "kk", "ky", "tg", "tk", "uz", "az", "ka", "hy"}) {
            assertThat(of(language, 1)).as(language).isEqualTo(PluralCategory.ONE);
            assertThat(of(language, 5)).as(language).isEqualTo(PluralCategory.OTHER);
        }
    }

}

