package eu.neydev.saver.core.i18n;

import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holder of the message bank with atomic hot reload:
 * the reload admin command swaps the reference to a new immutable {@link MessageRenderer},
 * renders in flight at that moment read the old snapshot - without locks or pauses.
 */
public final class MessageBundleHolder {

    private final String resourceBase;
    private final Path externalDir;
    private final Set<String> languages;
    private final String fallbackLanguage;
    private final MetricsRegistry metrics;

    private final AtomicReference<MessageRenderer> renderer = new AtomicReference<>();

    public MessageBundleHolder(String resourceBase, Path externalDir,
                               Set<String> languages, String fallbackLanguage) {
        this(resourceBase, externalDir, languages, fallbackLanguage, null);
    }

    public MessageBundleHolder(String resourceBase, Path externalDir,
                               Set<String> languages, String fallbackLanguage,
                               @org.jetbrains.annotations.Nullable MetricsRegistry metrics) {
        this.resourceBase = resourceBase;
        this.externalDir = externalDir;
        this.languages = languages;
        this.fallbackLanguage = fallbackLanguage;
        this.metrics = metrics;
        reload();
    }

    public void reload() {
        MessageBundle bundle = MessageBundle.load(resourceBase, externalDir, languages);
        renderer.set(new MessageRenderer(bundle, fallbackLanguage, metrics));
    }

    public @NotNull MessageRenderer renderer() {
        return renderer.get();
    }

    public MessageBundle bundle() {
        return renderer.get().bundle();
    }

    public Locale localeOf(String language) {
        return Locale.forLanguageTag(language);
    }

}

