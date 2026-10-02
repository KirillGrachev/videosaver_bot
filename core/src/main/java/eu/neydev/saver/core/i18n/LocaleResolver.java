package eu.neydev.saver.core.i18n;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Resolving the user's language: the platform profile language -> the supported set ->
 * the configuration default. The hint is normalized ({@code ru-RU} -> {@code ru});
 * an unsupported language is not an error - the default is simply taken.
 */
public final class LocaleResolver {

    private final Set<String> supported;
    private final String defaultLanguage;

    public LocaleResolver(Set<String> supported, String defaultLanguage) {
        this.supported = Set.copyOf(supported);
        this.defaultLanguage = defaultLanguage;
    }

    public Locale resolve(@Nullable String hint) {

        if (hint != null && !hint.isBlank()) {

            String language = Locale.forLanguageTag(hint.replace('_', '-')).getLanguage();

            if (supported.contains(language)) {
                return Locale.forLanguageTag(language);
            }

        }

        return Locale.forLanguageTag(defaultLanguage);

    }

    public Set<String> supported() {
        return supported;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public boolean isSupported(@NotNull String language) {
        return supported.contains(language);
    }

}

