package eu.neydev.saver.core.i18n;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * CLDR plural categories for every shipped language: the East Slavic branch (ru/uk/be),
 * West Slavic (pl/cs/sk), the "zero" branch (fr/pt, where one includes 0), the Baltic
 * branch (lt, and lv with its genitive-plural zero group carried as MANY), Romanian,
 * the South Slavic branch (hr, and sl with the dual as TWO), Irish and Maltese,
 * and a simple one/other for the rest.
 * Adding a language = one branch in {@link #of(Locale, long)}.
 */
public final class PluralRules {

    private static final Set<String> EAST_SLAVIC = Set.of("ru", "uk", "be");
    private static final Set<String> WEST_SLAVIC = Set.of("pl", "cs", "sk");
    private static final Set<String> ZERO_ONE = Set.of("fr", "pt");

    private PluralRules() {
    }

    public static PluralCategory of(@NotNull Locale locale, long n) {

        String language = locale.getLanguage();

        if (EAST_SLAVIC.contains(language)) {
            return eastSlavic(n);
        }

        if (WEST_SLAVIC.contains(language)) {
            return westSlavic(n);
        }

        if (ZERO_ONE.contains(language)) {
            return n == 0 || n == 1 ? PluralCategory.ONE : PluralCategory.OTHER;
        }

        return switch (language) {

            case "lt" -> lithuanian(n);
            case "lv" -> latvian(n);
            case "ro" -> romanian(n);
            case "hr" -> croatian(n);
            case "sl" -> slovenian(n);
            case "ga" -> irish(n);
            case "mt" -> maltese(n);
            default -> n == 1 ? PluralCategory.ONE : PluralCategory.OTHER;

        };

    }

    /** Lithuanian: 1 diena, 2-9 dienos (unless the teens), 10+ dienų. */
    private static PluralCategory lithuanian(long n) {

        long abs = Math.abs(n);
        long mod10 = abs % 10;
        long mod100 = abs % 100;

        if (mod10 == 1 && mod100 != 11) {
            return PluralCategory.ONE;
        }

        if (mod10 >= 2 && mod10 <= 9 && (mod100 < 11 || mod100 > 19)) {
            return PluralCategory.FEW;
        }

        return PluralCategory.OTHER;

    }

    /**
     * Latvian: 1 diena; the genitive-plural group (0 and the teens) takes the zero form,
     * carried as MANY because the template syntax has no zero category; the rest other.
     */
    private static PluralCategory latvian(long n) {

        long abs = Math.abs(n);
        long mod10 = abs % 10;
        long mod100 = abs % 100;

        if (mod10 == 1 && mod100 != 11) {
            return PluralCategory.ONE;
        }

        if (abs == 0 || mod10 == 0 || (mod100 >= 11 && mod100 <= 19)) {
            return PluralCategory.MANY;
        }

        return PluralCategory.OTHER;

    }

    /** Romanian: 1 zi, 0 and 2-19 (within each hundred) zile, the rest de zile. */
    private static PluralCategory romanian(long n) {

        long abs = Math.abs(n);
        long mod100 = abs % 100;

        if (abs == 1) {
            return PluralCategory.ONE;
        }

        if (abs == 0 || (mod100 >= 1 && mod100 <= 19)) {
            return PluralCategory.FEW;
        }

        return PluralCategory.OTHER;

    }

    /** Croatian: 1 dan, 2-4 dana (outside the teens), 5+ dana: one/few/other. */
    private static PluralCategory croatian(long n) {

        long abs = Math.abs(n);
        long mod10 = abs % 10;
        long mod100 = abs % 100;

        if (mod10 == 1 && mod100 != 11) {
            return PluralCategory.ONE;
        }

        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return PluralCategory.FEW;
        }

        return PluralCategory.OTHER;

    }

    /** Slovenian: the dual survives: 1 dan, 2 dneva, 3-4 dnevi, 5+ dni. */
    private static PluralCategory slovenian(long n) {

        long mod100 = Math.abs(n) % 100;

        if (mod100 == 1) {
            return PluralCategory.ONE;
        }

        if (mod100 == 2) {
            return PluralCategory.TWO;
        }

        if (mod100 == 3 || mod100 == 4) {
            return PluralCategory.FEW;
        }

        return PluralCategory.OTHER;

    }

    /** Irish: 1 lá, 2 lá, 3-6 lá, 7-10 lá, the rest other (five spoken forms). */
    private static PluralCategory irish(long n) {

        long abs = Math.abs(n);

        if (abs == 1) {
            return PluralCategory.ONE;
        }

        if (abs == 2) {
            return PluralCategory.TWO;
        }

        if (abs >= 3 && abs <= 6) {
            return PluralCategory.FEW;
        }

        if (abs >= 7 && abs <= 10) {
            return PluralCategory.MANY;
        }

        return PluralCategory.OTHER;

    }

    /** Maltese: 1, then 0 and 2-10 (within each hundred), then 11-19, then other. */
    private static PluralCategory maltese(long n) {

        long abs = Math.abs(n);
        long mod100 = abs % 100;

        if (abs == 1) {
            return PluralCategory.ONE;
        }

        if (abs == 0 || (mod100 >= 2 && mod100 <= 10)) {
            return PluralCategory.FEW;
        }

        if (mod100 >= 11 && mod100 <= 19) {
            return PluralCategory.MANY;
        }

        return PluralCategory.OTHER;

    }

    private static PluralCategory eastSlavic(long n) {

        long mod10 = Math.abs(n) % 10;
        long mod100 = Math.abs(n) % 100;

        if (mod10 == 1 && mod100 != 11) {
            return PluralCategory.ONE;
        }

        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return PluralCategory.FEW;
        }

        return PluralCategory.MANY;

    }

    private static PluralCategory westSlavic(long n) {

        long abs = Math.abs(n);
        long mod10 = abs % 10;
        long mod100 = abs % 100;

        if (abs == 1) {
            return PluralCategory.ONE;
        }

        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return PluralCategory.FEW;
        }

        return PluralCategory.MANY;

    }

}

