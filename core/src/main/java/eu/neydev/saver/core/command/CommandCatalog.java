package eu.neydev.saver.core.command;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A single command catalog: names, aliases, options and description keys.
 * A single source of truth for the core router and Discord slash-command registration -
 * names must never live in two places and drift apart.
 */
public final class CommandCatalog {

    /**
     * @param name           canonical name without a slash;
     * @param aliases        additional names (text platforms only);
     * @param descriptionKey i18n description key ({@code command.desc.*});
     * @param options        string option names (used by the slash form).
     */
    public record Spec(@NotNull String name,
                       @NotNull List<String> aliases,
                       @NotNull String descriptionKey,
                       @NotNull List<String> options) {

        public Spec(String name, String descriptionKey) {
            this(name, List.of(), descriptionKey, List.of());
        }

        public Spec(String name, String descriptionKey, List<String> options) {
            this(name, List.of(), descriptionKey, options);
        }

        public Spec(String name, List<String> aliases, String descriptionKey) {
            this(name, aliases, descriptionKey, List.of());
        }

    }

    public static final Spec START = new Spec("start", "command.desc.start");
    public static final Spec HELP = new Spec("help", "command.desc.help");
    public static final Spec SAVE = new Spec("save", List.of("download", "get", "dl"),
            "command.desc.save", List.of("url"));
    public static final Spec STOP = new Spec("stop", List.of("cancel"), "command.desc.stop");
    public static final Spec QUALITY = new Spec("quality", List.of("q"), "command.desc.quality");
    public static final Spec SETTINGS = new Spec("settings", "command.desc.settings");
    public static final Spec SOURCES = new Spec("sources", List.of("sites"), "command.desc.sources");
    public static final Spec STATS = new Spec("stats", "command.desc.stats");
    public static final Spec LANG = new Spec("lang", List.of("language"), "command.desc.lang");
    public static final Spec ABOUT = new Spec("about", List.of("info"), "command.desc.about");
    public static final Spec ADMIN = new Spec("admin", "command.desc.admin");
    public static final Spec RELOAD = new Spec("reload", "command.desc.reload");

    private static final List<Spec> ALL = List.of(
            START, HELP, SAVE, STOP, QUALITY, SETTINGS, SOURCES, STATS, LANG, ABOUT, ADMIN, RELOAD);

    private static final Map<String, Spec> BY_NAME_OR_ALIAS = ALL.stream()
            .flatMap(spec -> java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(spec.name()),
                    spec.aliases().stream())
                    .map(name -> Map.entry(name, spec)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private CommandCatalog() {
    }

    public static List<Spec> all() {
        return ALL;
    }

    /** Resolving a name or alias (without a slash) into a canonical specification. */
    public static Optional<Spec> resolve(@NotNull String nameWithoutSlash) {
        return Optional.ofNullable(
                BY_NAME_OR_ALIAS.get(nameWithoutSlash.toLowerCase(java.util.Locale.ROOT)));
    }

}
