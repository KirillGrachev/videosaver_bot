package eu.neydev.saver.app;

import eu.neydev.saver.core.i18n.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The style gate for everything a user can read: every shipped language must stay
 * free of the defects that are cheap to make and expensive to notice.
 *
 * <ul>
 *   <li>No em dash: it reads like a telegraph wire in a chat bubble; sentences connect
 *       with words or a plain hyphen.</li>
 *   <li>No doubled dot: an abbreviation ending in a dot plus a full stop prints ".."
 *       to every user; the ellipsis character exists for a reason.</li>
 *   <li>No "open source" loanword in the Russian copy: there the phrase is spelled out
 *       in Russian.</li>
 *   <li>Placeholder parity: a key whose Russian text promises {size} must carry {size}
 *       in English too, or the English user sees an unrendered brace.</li>
 * </ul>
 */
class MessageTextQualityTest {

    /** Every shipped language: a new bundle joins the style gate the moment it lands. */
    private static final Set<String> LANGUAGES = shippedLanguages();

    private static Set<String> shippedLanguages() {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", "messages"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Placeholder names with the optional plural clause: {@code {count|plural:...}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)(?:\\|[^}]*)?}");

    private final MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);

    @Test
    void noEmDashReachesAUser() {

        List<String> violations = new ArrayList<>();

        for (String language : LANGUAGES) {
            for (String key : bundle.keys(language)) {

                String value = bundle.get(key, language).orElse("");

                if (value.contains("\u2014") || value.contains("\u2013")) {
                    violations.add(language + ": " + key);
                }

            }
        }

        assertThat(violations)
                .as("em/en dashes in user-facing text")
                .isEmpty();

    }

    @Test
    void noSentenceEndsInADoubledDot() {

        List<String> violations = new ArrayList<>();

        for (String language : LANGUAGES) {
            for (String key : bundle.keys(language)) {
                String value = bundle.get(key, language).orElse("");

                if (value.contains("..")) {
                    violations.add(language + ": " + key + " -> " + value);
                }
            }
        }

        assertThat(violations)
                .as("doubled dots in user-facing text (use the ellipsis character)")
                .isEmpty();

    }

    @Test
    void russianCopyHasNoOpenSourceLoanword() {

        if (!LANGUAGES.contains("ru")) {
            return;
        }

        List<String> violations = new ArrayList<>();

        for (String key : bundle.keys("ru")) {

            String value = bundle.get(key, "ru").orElse("").toLowerCase(Locale.ROOT);

            if (value.contains("open source") || value.contains("open-source")
                    || value.contains("опенсорс") || value.contains("опен сорс")) {
                violations.add(key);
            }

        }

        assertThat(violations)
                .as("the Russian copy spells the phrase out in Russian")
                .isEmpty();

    }

    @Test
    void placeholdersAreTheSameInEveryLanguage() {

        List<String> violations = new ArrayList<>();

        for (String key : bundle.keys("en")) {

            Set<String> reference = placeholders(bundle.get(key, "en").orElse(""));

            for (String language : LANGUAGES) {

                if (language.equals("en")) {
                    continue;
                }

                Set<String> other = placeholders(bundle.get(key, language).orElse(""));

                if (!reference.equals(other)) {
                    violations.add(language + ": " + key + " " + reference + " vs " + other);
                }

            }

        }

        assertThat(violations)
                .as("placeholder sets diverge between languages")
                .isEmpty();

    }

    private static Set<String> placeholders(String template) {

        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);

        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        return names;

    }

}
