package eu.neydev.saver.core.i18n;

import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.text.RichText;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template rendering: substituting {@code {name}} placeholders and CLDR pluralization
 * {@code {days|plural:one=day|few=days|many=days}}. A missing category
 * falls back to OTHER, then to any presence - the template cannot fail at runtime.
 *
 * <p>The result is {@link RichText}: the mini-markup is parsed right here, once.
 */
public final class MessageRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{(\\w+)(?:\\|plural(:[^{}]*))?}");

    private static final Pattern PLURAL_FORM =
            Pattern.compile("[:|](one|two|few|many|other)=([^|{}]+)");

    private final MessageBundle bundle;
    private final String fallbackLanguage;
    private final @org.jetbrains.annotations.Nullable MetricsRegistry metrics;

    public MessageRenderer(MessageBundle bundle, String fallbackLanguage) {
        this(bundle, fallbackLanguage, null);
    }

    public MessageRenderer(MessageBundle bundle, String fallbackLanguage,
                           @org.jetbrains.annotations.Nullable MetricsRegistry metrics) {
        this.bundle = bundle;
        this.fallbackLanguage = fallbackLanguage;
        this.metrics = metrics;
    }

    public MessageBundle bundle() {
        return bundle;
    }

    public RichText rich(@NotNull String key, @NotNull Locale locale, @NotNull Map<String, Object> params) {
        return RichText.parse(raw(key, locale, params));
    }

    public RichText rich(@NotNull String key, @NotNull Locale locale) {
        return rich(key, locale, Map.of());
    }

    public String raw(@NotNull String key, @NotNull Locale locale) {
        return raw(key, locale, Map.of());
    }

    public String raw(@NotNull String key, @NotNull Locale locale, @NotNull Map<String, Object> params) {
        String template = template(key, locale);
        return substitute(template, locale, params);
    }

    private String template(String key, Locale locale) {

        String language = locale.getLanguage();

        return bundle.get(key, language)
                .or(() -> bundle.get(key, fallbackLanguage))
                .orElseGet(() -> {

                    if (metrics != null) {
                        metrics.increment("i18n_missing_total", "key", key);
                    }

                    return "[missing:" + key + "]";

                });

    }

    private String substitute(String template, Locale locale, Map<String, Object> params) {

        if (!template.contains("{")) {
            return template;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);

        while (matcher.find()) {

            String name = matcher.group(1);
            Object value = params.get(name);
            String replacement;

            if (matcher.group(2) != null) {
                replacement = pluralForm(matcher.group(2), locale, value);
            } else {
                replacement = RichText.escapeValue(String.valueOf(value));
            }

            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));

        }

        matcher.appendTail(out);
        return out.toString();

    }

    private String pluralForm(String formsSpec, Locale locale, Object value) {

        long number = value instanceof Number n ? n.longValue() : parseLongOrZero(String.valueOf(value));
        PluralCategory category = PluralRules.of(locale, number);

        Map<PluralCategory, String> forms = new java.util.EnumMap<>(PluralCategory.class);
        Matcher formMatcher = PLURAL_FORM.matcher(formsSpec);

        while (formMatcher.find()) {
            forms.put(PluralCategory.valueOf(formMatcher.group(1).toUpperCase(Locale.ROOT)),
                    formMatcher.group(2));
        }

        String chosen = forms.get(category);

        if (chosen == null) {
            chosen = forms.get(PluralCategory.OTHER);
        }

        if (chosen == null && !forms.isEmpty()) {
            chosen = forms.values().iterator().next();
        }

        return chosen == null ? String.valueOf(number) : chosen;

    }

    private static long parseLongOrZero(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}

