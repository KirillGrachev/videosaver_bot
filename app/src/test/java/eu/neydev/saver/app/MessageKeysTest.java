package eu.neydev.saver.app;

import eu.neydev.saver.core.i18n.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The i18n contract: every message key the code asks for exists in EVERY shipped
 * language, and all languages carry exactly the same key set.
 *
 * <p>Keys are collected by scanning the main sources of the whole repository for string
 * literals in the dotted namespaces, so a new key that is used but not translated fails
 * the build instead of reaching a user as {@code [missing:message.x]}. Keys assembled
 * dynamically ("message.error.cat." + category) are covered by
 * {@link ErrorCategoryKeysTest} instead - both nets together leave no blind spot.
 */
class MessageKeysTest {

    /** Every language that ships a bundle: the gate widens on its own when a file is added. */
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

    /** Dotted message keys as they appear in the code: {@code "message.status.done"}. */
    private static final Pattern KEY = Pattern.compile(
            "\"((?:message|button|command)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*)\"");

    @Test
    void everyKeyUsedInCodeExistsInEveryLanguage() throws IOException {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);
        Set<String> used = usedKeys();

        List<String> missing = new ArrayList<>();

        for (String key : used) {
            for (String language : LANGUAGES) {
                if (bundle.get(key, language).isEmpty()) {
                    missing.add(language + ": " + key);
                }
            }
        }

        assertThat(missing)
                .as("keys used in the code but absent from a language file")
                .isEmpty();

    }

    @Test
    void allLanguagesCarryTheSameKeySet() {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);
        Set<String> reference = new TreeSet<>(bundle.keys("en"));

        for (String language : LANGUAGES) {

            if (language.equals("en")) {
                continue;
            }

            Set<String> other = new TreeSet<>(bundle.keys(language));
            Set<String> onlyEn = new TreeSet<>(reference);
            onlyEn.removeAll(other);
            Set<String> onlyOther = new TreeSet<>(other);
            onlyOther.removeAll(reference);

            assertThat(onlyEn).as("keys missing in " + language).isEmpty();
            assertThat(onlyOther).as("keys extra in " + language).isEmpty();

        }

    }

    @Test
    void everyDynamicErrorCategoryHasAMessage() {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);

        for (var category : eu.neydev.saver.core.extract.ExtractionException.Category.values()) {

            String key = "message.error.cat." + category.id();

            for (String language : LANGUAGES) {
                assertThat(bundle.get(key, language))
                        .as("%s in %s", key, language)
                        .isPresent();
            }

        }

    }

    @Test
    void everyQualityPresetHasAButtonLabel() {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);

        for (var preset : eu.neydev.saver.core.extract.QualityPreset.values()) {

            String key = "button.quality." + preset.id();

            for (String language : LANGUAGES) {
                assertThat(bundle.get(key, language))
                        .as("%s in %s", key, language)
                        .isPresent();
            }

        }

    }

    @Test
    void everyRejectionKindHasAMessage() {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);

        for (var rejection : eu.neydev.saver.core.download.JobManager.Rejection.values()) {

            for (String language : LANGUAGES) {
                assertThat(bundle.get(rejection.messageKey(), language))
                        .as("%s in %s", rejection.messageKey(), language)
                        .isPresent();
            }

        }

    }

    /** Multi-line templates must stay multi-line: a YAML list is joined with newlines. */
    @Test
    void multilineTemplatesKeepTheirLineBreaks() {

        MessageBundle bundle = MessageBundle.load("messages", null, LANGUAGES);

        for (String language : LANGUAGES) {

            String start = bundle.get("message.start", language).orElseThrow();
            assertThat(start)
                    .as("message.start in " + language)
                    .contains("\n")
                    .doesNotStartWith("[")
                    .doesNotContain(", ,");

        }

    }

    private static Set<String> usedKeys() throws IOException {

        Set<String> keys = new TreeSet<>();

        try (Stream<Path> files = Files.walk(repositoryRoot())) {

            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> p.toString().endsWith(".java")).toList()) {

                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) keys.add(matcher.group(1));

            }

        }

        return keys;

    }

    private static Path repositoryRoot() {

        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("core")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }

        throw new IllegalStateException("repository root not found");

    }

}
