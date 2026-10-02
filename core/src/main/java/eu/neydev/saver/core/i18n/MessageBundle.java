package eu.neydev.saver.core.i18n;

import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable message bank: {@code messages/<lang>.yml} on the classpath or in
 * an external directory (external takes priority - edits without a rebuild).
 *
 * <p>File structure - flat dotted keys or nested maps; nesting
 * expands into dotted keys ({@code message.start.title}).
 *
 * <p>Hot reload: {@link #load} is called again, the holder swaps
 * swaps the reference atomically (see MessageBundleHolder).
 */
public final class MessageBundle {

    private final Map<String, Map<String, String>> byLanguage;
    private final Set<String> languages;

    private MessageBundle(Map<String, Map<String, String>> byLanguage) {
        this.byLanguage = Map.copyOf(byLanguage);
        this.languages = Set.copyOf(byLanguage.keySet());
    }

    public Set<String> languages() {
        return languages;
    }

    public Optional<String> get(@NotNull String key, @NotNull String language) {
        Map<String, String> messages = byLanguage.get(language);
        return messages == null ? Optional.empty() : Optional.ofNullable(messages.get(key));
    }

    /** The full key set of one language - the basis of translation-completeness checks. */
    public Set<String> keys(@NotNull String language) {

        Map<String, String> messages = byLanguage.get(language);

        return messages == null ? Set.of() : Set.copyOf(messages.keySet());

    }

    /**
     * @param resourceBase name of the messages resource directory (for example {@code messages});
     * @param externalDir  external directory with the same files or {@code null};
     * @param languages    languages that must load (fail-fast).
     */
    public static MessageBundle load(@NotNull String resourceBase,
                                     Path externalDir,
                                     @NotNull Set<String> languages) {

        Map<String, Map<String, String>> result = new HashMap<>();

        for (String language : languages) {

            Map<String, String> messages = loadLanguage(resourceBase, externalDir, language);

            if (messages.isEmpty()) {
                throw new IllegalStateException(
                        "No messages found for language '%s' (resource %s/%s.yml or external directory)"
                                .formatted(language, resourceBase, language));
            }

            result.put(language, messages);

        }

        return new MessageBundle(result);

    }

    /**
     * Like {@link #load}, but a language without a file simply stays absent instead of
     * failing the startup: reference catalogs (city names) ship for the languages that
     * need them and fall back per key at read time.
     */
    public static MessageBundle loadPresent(@NotNull String resourceBase,
                                            Path externalDir,
                                            @NotNull Set<String> languages) {

        Map<String, Map<String, String>> result = new HashMap<>();

        for (String language : languages) {

            Map<String, String> messages = loadLanguage(resourceBase, externalDir, language);

            if (!messages.isEmpty()) {
                result.put(language, messages);
            }

        }

        return new MessageBundle(result);

    }

    private static Map<String, String> loadLanguage(String resourceBase, Path externalDir, String language) {

        Map<?, ?> raw = null;
        Path externalFile = externalDir == null ? null : externalDir.resolve(language + ".yml");

        if (externalFile != null && Files.isReadable(externalFile)) {
            try (InputStream in = Files.newInputStream(externalFile)) {
                raw = new Yaml().load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read external messages file " + externalFile, e);
            }
        } else {

            String resource = resourceBase + "/" + language + ".yml";
            try (InputStream in = MessageBundle.class.getClassLoader().getResourceAsStream(resource)) {
                if (in != null) {
                    raw = new Yaml().load(in);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read resource " + resource, e);
            }

        }

        Map<String, String> flat = new HashMap<>();

        if (raw != null) {
            flatten("", raw, flat);
        }

        return flat;

    }

    private static void flatten(String prefix, Map<?, ?> node, Map<String, String> out) {

        for (Map.Entry<?, ?> entry : node.entrySet()) {

            String rawKey = String.valueOf(entry.getKey());
            String key = prefix.isEmpty() ? rawKey : prefix + "." + rawKey;
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, out);
            } else if (value instanceof List<?> lines) {

                // A YAML list is a multi-line message. String.valueOf(list) would render it
                // as "[line1, line2]" - brackets, commas and no line breaks, which is exactly
                // what the user saw in the chat.
                StringBuilder joined = new StringBuilder();

                for (Object line : lines) {
                    if (joined.length() > 0) {
                        joined.append('\n');
                    }

                    joined.append(line);

                }

                out.put(key, joined.toString());

            } else if (value != null) {
                out.put(key, String.valueOf(value));
            }
        }
    }

    public static Locale localeOf(String language) {
        return Locale.forLanguageTag(language);
    }

}

