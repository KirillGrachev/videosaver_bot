package eu.neydev.saver.core.source;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of supported sources, assembled at startup from three layers:
 *
 * <ol>
 *   <li>the built-in {@code sources.yml} resource - the shipped catalog;</li>
 *   <li>an optional external {@code config/sources.yml} - entries with the same id
 *       OVERRIDE the built-in ones, new ids are appended (an operator can fix a broken
 *       host pattern or add a niche site without a rebuild);</li>
 *   <li>{@code sources.disabled} from the configuration - a disabled entry stays in the
 *       catalog (so URLs still resolve to a source NAME) but jobs against it are
 *       rejected with an honest "disabled by the administrator" message.</li>
 * </ol>
 *
 * <p>Immutable after load; {@code /reload} swaps the whole catalog atomically.
 */
public final class SourceCatalog {

    private static final Logger log = LoggerFactory.getLogger(SourceCatalog.class);

    /** The pseudo-source for unrecognized hosts: everything falls back to the web scraper. */
    public static final String GENERIC_ID = "web";

    public static final Source GENERIC = new Source(GENERIC_ID, "Web", List.of(),
            SourceBackend.HTTP, SourceStatus.OK, null);

    private final Map<String, Source> byId;
    private final List<Source> ordered;
    private final Set<String> disabled;

    private SourceCatalog(Map<String, Source> byId, Set<String> disabled) {
        this.byId = Map.copyOf(byId);
        this.ordered = List.copyOf(byId.values());
        this.disabled = Set.copyOf(disabled);
    }

    public static SourceCatalog load(Set<String> disabledIds, @Nullable Path externalFile) {

        Map<String, Source> sources = new LinkedHashMap<>();
        sources.put(GENERIC.id(), GENERIC);

        parseYaml(builtinStream(), "classpath:sources.yml").forEach(s -> sources.put(s.id(), s));

        if (externalFile != null && Files.isReadable(externalFile)) {

            try (InputStream in = Files.newInputStream(externalFile)) {
                parseYaml(in, externalFile.toString()).forEach(s -> {

                    // "web" is the service id of the generic fallback: overriding it
                    // would break every unmatched URL in a way no error message explains.
                    if (GENERIC_ID.equals(s.id())) {
                        throw new IllegalStateException(
                                "External catalog must not override the service id '"
                                        + GENERIC_ID + "' (the generic fallback): " + externalFile);
                    }

                    if (sources.containsKey(s.id())) {
                        log.info("Source '{}' overridden by {}", s.id(), externalFile);
                    }

                    sources.put(s.id(), s);

                });
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + externalFile, e);
            }

        }

        for (String id : disabledIds) {
            if (!sources.containsKey(id)) {
                log.warn("sources.disabled mentions an unknown source id: {} (ignored)", id);
            }
        }

        log.info("Source catalog loaded: {} sources ({} disabled)", sources.size(), disabledIds.size());

        return new SourceCatalog(sources, disabledIds);

    }

    public Collection<Source> all() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }

    public Optional<Source> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean isDisabled(String id) {
        return disabled.contains(id);
    }

    public Set<String> disabled() {
        return disabled;
    }

    /** Case-insensitive substring search over ids and titles, for the /sources dialog. */
    public List<Source> search(String query) {

        String needle = query.trim().toLowerCase(Locale.ROOT);

        if (needle.isEmpty()) {
            return ordered;
        }

        List<Source> exact = new ArrayList<>();
        List<Source> partial = new ArrayList<>();

        for (Source source : ordered) {

            String id = source.id();
            String title = source.title().toLowerCase(Locale.ROOT);

            if (id.equals(needle) || title.equals(needle)) {
                exact.add(source);
            } else if (id.startsWith(needle) || title.startsWith(needle)) {
                exact.add(source);
            } else if (id.contains(needle) || title.contains(needle)) {
                partial.add(source);
            }

        }

        exact.addAll(partial);

        return exact;

    }

    private static InputStream builtinStream() {

        InputStream in = SourceCatalog.class.getClassLoader().getResourceAsStream("sources.yml");

        if (in == null) {
            throw new IllegalStateException("Built-in sources.yml is missing from the classpath");
        }

        return in;

    }

    @SuppressWarnings("unchecked")
    private static List<Source> parseYaml(InputStream in, String origin) {

        Map<String, Object> root;

        try {
            root = new Yaml().load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid YAML in " + origin + ": " + e.getMessage(), e);
        }

        if (root == null || !(root.get("sources") instanceof List<?> list)) {
            throw new IllegalStateException("sources.yml must contain a 'sources' list: " + origin);
        }

        List<Source> result = new ArrayList<>(list.size());

        for (Object entry : list) {

            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Source entry is not a map in " + origin);
            }

            Map<String, Object> fields = (Map<String, Object>) map;
            String id = str(fields, "id", origin);
            String title = fields.get("title") instanceof String t && !t.isBlank()
                    ? t
                    : id;

            List<String> hosts = new ArrayList<>();

            if (fields.get("hosts") instanceof List<?> hostList) {
                hostList.forEach(h -> hosts.add(String.valueOf(h)));
            }

            SourceBackend backend = SourceBackend.fromIdOrDefault(
                    String.valueOf(fields.getOrDefault("backend", "ytdlp")), SourceBackend.YTDLP);
            SourceStatus status = SourceStatus.fromIdOrDefault(
                    String.valueOf(fields.getOrDefault("status", "ok")), SourceStatus.OK);
            String note = fields.get("note") instanceof String n && !n.isBlank() ? n : null;

            result.add(new Source(id.toLowerCase(Locale.ROOT), title, hosts, backend, status, note));

        }

        return result;

    }

    private static String str(Map<String, Object> fields, String key, String origin) {

        Object value = fields.get(key);

        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("Source entry without '" + key + "' in " + origin);
        }

        return s;

    }

}
