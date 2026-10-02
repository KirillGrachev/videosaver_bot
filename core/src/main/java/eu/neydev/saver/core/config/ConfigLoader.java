package eu.neydev.saver.core.config;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.util.ByteFormat;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code application.yml} loader: SnakeYAML -> Map -> typed records with validation and
 * human-readable errors. Secrets come from the environment: {@code ${VAR}} (required)
 * and {@code ${VAR:default}} (optional), resolved through env vars first and
 * {@code config/.env} / {@code .env} second.
 *
 * <p>Strict schema: a typo in a config key fails the startup with the exact key path
 * instead of silently ignoring the operator's intent.
 */
public record ConfigLoader(Function<String, String> environment) {

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    private static final Set<String> ROOT_KEYS = Set.of(
            "storage", "downloader", "limits", "delivery", "pipeline", "webapp", "status",
            "community", "locale", "sources", "platforms", "owners");

    private static final Map<String, Set<String>> SECTION_KEYS = Map.of(
            "storage", Set.of("type", "sqlite-path", "jdbc-url", "username", "password", "pool-size"),
            "downloader", Set.of("workers", "queue-capacity", "per-user-concurrent",
                    "max-items-per-request", "extract-timeout", "download-timeout",
                    "max-file-size", "default-quality", "allow-private-networks",
                    "proxy", "cookies-file", "cookies-owner-only", "per-source-per-second",
                    "sleep-requests", "job-log-retention", "shutdown-drain",
                    "write-subs", "audio-thumbnail", "blocked-extensions", "tools", "vault"),
            "limits", Set.of("jobs-per-user-per-hour", "jobs-per-user-per-day",
                    "bytes-per-user-per-day"),
            "delivery", Set.of("link-fallback", "caps"),
            "pipeline", Set.of("inbound-queue-capacity", "worker-threads",
                    "inbound-per-user-per-window", "outbound-workers-per-platform",
                    "outbound-global-per-second", "outbound-per-chat-per-minute", "send-timeout"),
            "webapp", Set.of("enabled", "bind-host", "port", "public-url",
                    "media-link-ttl", "metrics-token"),
            "community", Set.of("github-url"),
            "locale", Set.of("default", "supported", "display-names"),
            "sources", Set.of("disabled"),
            "status", Set.of("heartbeat-interval", "liveness-file"));

    private static final Set<String> TOOLS_KEYS =
            Set.of("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe", "update-interval",
                    "provision", "provision-dir");

    private static final Set<String> VAULT_KEYS =
            Set.of("dir", "max-size", "ttl", "send-grace");

    private static final Set<String> CAPS_KEYS =
            Set.of("photo", "video", "audio", "gif", "document");

    private static final Set<String> PLATFORM_KEYS =
            Set.of("enabled", "token", "id", "secret", "proxy");

    /** Allowed proxy schemes in {@code downloader.proxy} and {@code platforms.*.proxy}. */
    private static final Set<String> PROXY_PREFIXES = Set.of("http://", "https://", "socks5://", "socks://");

    public ConfigLoader() {
        this(name -> {
            String value = System.getenv(name);
            return value != null ? value : DotEnv.get(name);
        });
    }

    public AppConfig load(@Nullable Path externalFile) {
        Map<String, Object> root = readYaml(externalFile);
        return parse(root);
    }

    private Map<String, Object> readYaml(@Nullable Path externalFile) {

        if (externalFile != null && Files.isReadable(externalFile)) {
            try (InputStream in = Files.newInputStream(externalFile)) {
                return new Yaml().load(in);
            } catch (IOException e) {
                throw new ConfigException("Cannot read file " + externalFile, e);
            }
        }

        return loadDefaultResource();

    }

    private Map<String, Object> loadDefaultResource() {
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new ConfigException("application.yml", "neither external file nor resource found");
            }

            return new Yaml().load(in);
        } catch (IOException e) {
            throw new ConfigException("Cannot read application.yml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private AppConfig parse(Map<String, Object> root) {

        if (root == null) {
            throw new ConfigException("<root>", "empty YAML");
        }

        validateKeys(root);

        return new AppConfig(
                parseStorage(section(root, "storage")),
                parseDownloader(section(root, "downloader")),
                parseLimits(section(root, "limits")),
                parseDelivery(section(root, "delivery")),
                parsePipeline(section(root, "pipeline")),
                parseWebApp(section(root, "webapp")),
                parseLocale(section(root, "locale")),
                new AppConfig.Sources(stringList(get(section(root, "sources"), "disabled"), List.of())),
                new AppConfig.Community(str(section(root, "community"), "github-url", null)),
                parsePlatforms(section(root, "platforms")),
                stringList(get(root, "owners"), List.of()),
                parseStatus(section(root, "status")));

    }

    private AppConfig.Storage parseStorage(Map<String, Object> node) {

        String type = str(node, "type", "sqlite");
        AppConfig.Storage.Type storageType;

        try {
            storageType = AppConfig.Storage.Type.valueOf(type.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("storage.type", "unknown type '" + type + "' (sqlite | postgres)");
        }

        return new AppConfig.Storage(
                storageType,
                str(node, "sqlite-path", "data/saver.db"),
                str(node, "jdbc-url", null),
                str(node, "username", null),
                str(node, "password", null),
                positiveInt(node, "pool-size", 8));

    }

    private AppConfig.Downloader parseDownloader(Map<String, Object> node) {

        Map<String, Object> tools = section(node, "tools");
        validateSubKeys("downloader.tools", tools, TOOLS_KEYS);

        Map<String, Object> vault = section(node, "vault");
        validateSubKeys("downloader.vault", vault, VAULT_KEYS);

        String proxy = str(node, "proxy", null);
        validateProxy("downloader.proxy", proxy);

        return new AppConfig.Downloader(
                positiveInt(node, "workers", 4),
                positiveInt(node, "queue-capacity", 200),
                positiveInt(node, "per-user-concurrent", 2),
                positiveInt(node, "max-items-per-request", 10),
                duration(node, "extract-timeout", Duration.ofMinutes(2)),
                duration(node, "download-timeout", Duration.ofMinutes(15)),
                size(node, "max-file-size", "downloader.max-file-size",
                        ByteFormat.parse("500mb", "downloader.max-file-size")),
                QualityPreset.fromIdOrDefault(
                        str(node, "default-quality", "best"), QualityPreset.BEST),
                bool(node, "allow-private-networks", false),
                proxy,
                str(node, "cookies-file", null),
                bool(node, "cookies-owner-only", true),
                positiveDouble(node, "per-source-per-second", 2.0),
                duration(node, "sleep-requests", Duration.ofMillis(500)),
                duration(node, "job-log-retention", Duration.ofDays(90)),
                duration(node, "shutdown-drain", Duration.ofSeconds(30)),
                bool(node, "write-subs", false),
                bool(node, "audio-thumbnail", false),
                blockedExtensions(node),
                new AppConfig.Downloader.Tools(
                        str(tools, "yt-dlp", "yt-dlp"),
                        str(tools, "gallery-dl", "gallery-dl"),
                        str(tools, "ffmpeg", "ffmpeg"),
                        str(tools, "ffprobe", "ffprobe"),
                        duration(tools, "update-interval", Duration.ZERO),
                        bool(tools, "provision", true),
                        str(tools, "provision-dir", "tools")),
                new AppConfig.Downloader.Vault(
                        str(vault, "dir", "data/media"),
                        size(vault, "max-size", "downloader.vault.max-size",
                                ByteFormat.parse("10gb", "downloader.vault.max-size")),
                        duration(vault, "ttl", Duration.ofMinutes(30)),
                        duration(vault, "send-grace", Duration.ofMinutes(10))));

    }

    /**
     * The blocklist of delivered extensions. An EMPTY YAML list means "block nothing"
     * (an operator decision, not a default); an absent key means the built-in list of
     * executable/script types.
     */
    private static List<String> blockedExtensions(Map<String, Object> node) {

        Object raw = node.get("blocked-extensions");

        if (raw == null) {
            return AppConfig.Downloader.DEFAULT_BLOCKED_EXTENSIONS;
        }

        return stringList(raw, List.of()).stream()
                .map(ext -> ext.toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\.", ""))
                .filter(ext -> !ext.isBlank())
                .toList();

    }

    private AppConfig.Limits parseLimits(Map<String, Object> node) {

        return new AppConfig.Limits(
                positiveInt(node, "jobs-per-user-per-hour", 20),
                positiveInt(node, "jobs-per-user-per-day", 200),
                size(node, "bytes-per-user-per-day", "limits.bytes-per-user-per-day",
                        ByteFormat.parse("2gb", "limits.bytes-per-user-per-day")));

    }

    @SuppressWarnings("unchecked")
    private AppConfig.Delivery parseDelivery(Map<String, Object> node) {

        Map<Platform, AppConfig.Delivery.SizeCaps> caps = new EnumMap<>(Platform.class);
        Map<String, Object> capsNode = section(node, "caps");

        for (Map.Entry<String, Object> entry : capsNode.entrySet()) {

            Platform platform;

            try {
                platform = Platform.fromId(entry.getKey());
            } catch (IllegalArgumentException e) {
                throw new ConfigException("delivery.caps." + entry.getKey(), "unknown platform");
            }

            caps.put(platform, parseCaps("delivery.caps." + platform.id(), platform, entry.getValue()));

        }

        return new AppConfig.Delivery(caps, bool(node, "link-fallback", true));

    }

    /**
     * Per-platform caps. A scalar is shorthand for "the same cap for every media kind";
     * a map overrides per kind. Missing kinds fall back to the published Bot API limits
     * of THAT platform (conservative: an optimistic default fails at send time with a
     * platform error the user cannot act on).
     */
    @SuppressWarnings("unchecked")
    private AppConfig.Delivery.SizeCaps parseCaps(String path, Platform platform, Object value) {

        long defaultVideo = switch (platform) {
            case TELEGRAM -> mb(50);
            case VK -> mb(200);
            case DISCORD -> mb(25);
            case WHATSAPP -> mb(16);
            case VIBER -> mb(26);
            case SLACK -> mb(100);
        };

        long defaultPhoto = switch (platform) {
            case TELEGRAM -> mb(10);
            case WHATSAPP -> mb(5);
            case VIBER -> mb(1);
            default -> defaultVideo;
        };

        if (value instanceof Number || (value instanceof String s && !s.isBlank() && !s.contains(":"))) {

            long bytes = ByteFormat.parse(String.valueOf(value), path);
            return AppConfig.Delivery.SizeCaps.uniform(bytes);

        }

        if (!(value instanceof Map<?, ?> map)) {
            throw new ConfigException(path, "expected a size (50mb) or a map of per-kind sizes");
        }

        Map<String, Object> node = (Map<String, Object>) map;

        for (String key : node.keySet()) {
            if (!CAPS_KEYS.contains(key)) {
                throw new ConfigException(path + "." + key,
                        "unknown media kind; allowed: " + new java.util.TreeSet<>(CAPS_KEYS));
            }
        }

        long video = size(node, "video", path + ".video", defaultVideo);

        return new AppConfig.Delivery.SizeCaps(
                size(node, "photo", path + ".photo", defaultPhoto),
                video,
                size(node, "audio", path + ".audio", video),
                size(node, "gif", path + ".gif", video),
                size(node, "document", path + ".document", Math.max(video, defaultVideo)));

    }

    private static long mb(long megabytes) {
        return megabytes * 1_000_000L;
    }

    private AppConfig.Pipeline parsePipeline(Map<String, Object> node) {
        return new AppConfig.Pipeline(
                positiveInt(node, "inbound-queue-capacity", 10_000),
                positiveInt(node, "worker-threads", 16),
                positiveInt(node, "inbound-per-user-per-window", 8),
                positiveInt(node, "outbound-workers-per-platform", 4),
                positiveDouble(node, "outbound-global-per-second", 24.0),
                positiveInt(node, "outbound-per-chat-per-minute", 18),
                duration(node, "send-timeout", Duration.ofSeconds(15)));
    }

    private AppConfig.WebApp parseWebApp(Map<String, Object> node) {
        return new AppConfig.WebApp(
                bool(node, "enabled", false),
                str(node, "bind-host", "127.0.0.1"),
                positiveInt(node, "port", 8080),
                str(node, "public-url", null),
                duration(node, "media-link-ttl", Duration.ofMinutes(5)),
                str(node, "metrics-token", null));
    }

    private AppConfig.Locale parseLocale(Map<String, Object> node) {

        Set<String> supported = new LinkedHashSet<>(stringList(get(node, "supported"), List.of("ru", "en")));
        String def = str(node, "default", "ru");

        if (!supported.contains(def)) {
            supported.add(def);
        }

        return new AppConfig.Locale(def, supported, parseDisplayNames(node, supported));

    }

    /**
     * {@code locale.display-names}: language code -> button caption. A code that is
     * not listed in {@code locale.supported} is a typo and fails the startup;
     * a supported language without a caption falls back to the code itself.
     */
    private Map<String, String> parseDisplayNames(Map<String, Object> node, Set<String> supported) {

        Object raw = get(node, "display-names");

        if (raw == null) {
            return Map.of();
        }

        if (!(raw instanceof Map<?, ?> map)) {
            throw new ConfigException("locale.display-names", "expected a map of language code -> name");
        }

        Map<String, String> names = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : map.entrySet()) {

            String code = String.valueOf(entry.getKey());

            if (!supported.contains(code)) {
                throw new ConfigException("locale.display-names." + code,
                        "language is not listed in locale.supported");
            }

            if (!(entry.getValue() instanceof String name) || name.isBlank()) {
                throw new ConfigException("locale.display-names." + code, "expected a non-empty string");
            }

            names.put(code, name);

        }

        return names;

    }

    private AppConfig.Status parseStatus(Map<String, Object> node) {
        return new AppConfig.Status(
                duration(node, "heartbeat-interval", AppConfig.Status.DEFAULT_HEARTBEAT_INTERVAL),
                str(node, "liveness-file", AppConfig.Status.DEFAULT_LIVENESS_FILE));
    }

    @SuppressWarnings("unchecked")
    private Map<Platform, AppConfig.PlatformSection> parsePlatforms(Map<String, Object> node) {

        Map<Platform, AppConfig.PlatformSection> result = new EnumMap<>(Platform.class);

        for (Platform platform : Platform.values()) {
            Object raw = node.get(platform.id());
            Map<String, Object> platformSection =
                    raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            String proxy = str(platformSection, "proxy", null);
            validateProxy("platforms." + platform.id() + ".proxy", proxy);
            result.put(platform, new AppConfig.PlatformSection(
                    bool(platformSection, "enabled", false),
                    str(platformSection, "token", null),
                    str(platformSection, "id", null),
                    str(platformSection, "secret", null),
                    proxy));
        }

        return result;

    }

    /**
     * Proxy format: {@code [http://|socks5://]host:port}, optionally with a login -
     * {@code user:pass@host:port} or the colon form proxy sellers print in their lists,
     * {@code host:port:user:pass} (a SOCKS5 login, RFC 1929). An error in the address is
     * visible at start, not at the moment of the first network request.
     */
    private static void validateProxy(String key, @Nullable String proxy) {

        if (proxy == null || proxy.isBlank()) {
            return;
        }

        String value = proxy.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);

        for (String prefix : PROXY_PREFIXES) {
            if (lower.startsWith(prefix)) {
                value = value.substring(prefix.length());
                break;
            }
        }

        int at = value.lastIndexOf('@');

        if (at >= 0) {
            value = value.substring(at + 1);
        }

        List<String> parts = splitOutsideBrackets(value);

        if (parts.size() != 2 && parts.size() != 4) {
            throw new ConfigException(key, "expected [http://|socks5://]host:port[:user:pass], got '"
                    + proxy + "'");
        }

        if (parts.get(0).isEmpty()) {
            throw new ConfigException(key, "no host before the port in '" + proxy + "'");
        }

        try {
            int port = Integer.parseInt(parts.get(1));
            if (port < 1 || port > 65_535) {
                throw new NumberFormatException("port out of range");
            }
        } catch (NumberFormatException e) {
            throw new ConfigException(key, "invalid port in '" + proxy + "'");
        }

    }

    /** Splits on ':' outside a bracketed IPv6 literal, so {@code [::1]:1080} stays whole. */
    private static List<String> splitOutsideBrackets(String value) {

        List<String> parts = new ArrayList<>();

        int depth = 0;
        int start = 0;

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ':' && depth == 0) {
                parts.add(value.substring(start, i).trim());
                start = i + 1;
            }

        }

        parts.add(value.substring(start).trim());
        return parts;

    }

    /** Strict schema: a typo in a config key is visible immediately at start. */
    @SuppressWarnings("unchecked")
    private void validateKeys(Map<String, Object> root) {

        for (String key : root.keySet()) {
            if (!ROOT_KEYS.contains(key)) {
                throw new ConfigException(key, "unknown top-level key; allowed: "
                        + new java.util.TreeSet<>(ROOT_KEYS));
            }
        }

        SECTION_KEYS.forEach((sectionName, allowed) -> {

            Object node = root.get(sectionName);

            if (node instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (!allowed.contains(String.valueOf(key))) {
                        throw new ConfigException(sectionName + "." + key,
                                "unknown key; allowed: " + new java.util.TreeSet<>(allowed));
                    }
                }
            }

        });

        Object platforms = root.get("platforms");

        if (platforms instanceof Map<?, ?> platformMap) {

            for (Map.Entry<?, ?> entry : platformMap.entrySet()) {

                String platform = String.valueOf(entry.getKey());

                try {
                    Platform.fromId(platform);
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("platforms." + platform, "unknown platform");
                }

                if (entry.getValue() instanceof Map<?, ?> platformSection) {
                    for (Object key : platformSection.keySet()) {
                        if (!PLATFORM_KEYS.contains(String.valueOf(key))) {
                            throw new ConfigException("platforms." + platform + "." + key,
                                    "unknown key; allowed: " + new java.util.TreeSet<>(PLATFORM_KEYS));
                        }
                    }
                }
            }
        }

        Object delivery = root.get("delivery");

        if (delivery instanceof Map<?, ?> deliveryMap
                && deliveryMap.get("caps") instanceof Map<?, ?> capsMap) {

            for (Object key : capsMap.keySet()) {

                try {
                    Platform.fromId(String.valueOf(key));
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("delivery.caps." + key, "unknown platform");
                }

            }

        }

    }

    private static void validateSubKeys(String path, Map<String, Object> node, Set<String> allowed) {

        for (String key : node.keySet()) {
            if (!allowed.contains(key)) {
                throw new ConfigException(path + "." + key,
                        "unknown key; allowed: " + new java.util.TreeSet<>(allowed));
            }
        }

    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object value = root.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object get(Map<String, Object> node, String key) {
        return node.get(key);
    }

    private String str(Map<String, Object> node, String key, String def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        return substitute(String.valueOf(value), key);

    }

    private String substitute(String value, String key) {

        Matcher matcher = ENV.matcher(value);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {

            String envValue = environment.apply(matcher.group(1));

            if (envValue == null && matcher.group(2) == null) {
                throw new ConfigException(key,
                        "environment variable '%s' is not set (secrets are not stored in YAML)"
                                .formatted(matcher.group(1)));
            }

            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    envValue != null ? envValue : matcher.group(2)));

        }

        matcher.appendTail(out);
        return out.toString();

    }

    private boolean bool(Map<String, Object> node, String key, boolean def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        if (value instanceof Boolean b) {
            return b;
        }

        return Boolean.parseBoolean(String.valueOf(value));

    }

    private int positiveInt(Map<String, Object> node, String key, int def) {

        int value = intOf(node, key, def);

        if (value <= 0) {
            throw new ConfigException(key, "expected a positive number, got " + value);
        }

        return value;

    }

    private int intOf(Map<String, Object> node, String key, int def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        if (value instanceof Number n) {
            return n.intValue();
        }

        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key, "not a number: " + value);
        }

    }

    private double positiveDouble(Map<String, Object> node, String key, double def) {

        Object value = node.get(key);
        double parsed = value == null ? def : value instanceof Number n ? n.doubleValue()
                : Double.parseDouble(String.valueOf(value).trim());

        if (parsed <= 0) {
            throw new ConfigException(key, "expected a positive number, got " + parsed);
        }

        return parsed;

    }

    /** A byte-size value: "500mb", "2gb", "10485760" - parsed by {@link ByteFormat}. */
    private long size(Map<String, Object> node, String key, String path, long def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        if (value instanceof Number n) {
            return n.longValue();
        }

        try {
            return ByteFormat.parse(substitute(String.valueOf(value), key), path);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(path, e.getMessage());
        }

    }

    private Duration duration(Map<String, Object> node, String key, Duration def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        String raw = String.valueOf(value).trim();

        try {

            if (raw.matches("\\d+")) {
                return Duration.ofSeconds(Long.parseLong(raw));
            }

            // Milliseconds matter for tool politeness knobs (sleep-requests: 500ms);
            // ISO-8601 spells them PT0.5S, which no human writes by accident.
            if (raw.matches("(?i)\\d+ms")) {
                return Duration.ofMillis(Long.parseLong(raw.substring(0, raw.length() - 2)));
            }

            // Days belong to the date part of an ISO-8601 duration ("P400D"), so "400d"
            // cannot be reached by prefixing "PT" - it is converted explicitly.
            if (raw.matches("(?i)\\d+d")) {
                return Duration.ofDays(Long.parseLong(raw.substring(0, raw.length() - 1)));
            }

            return Duration.parse(raw.startsWith("PT") || raw.startsWith("P") ? raw : "PT" + raw);

        } catch (Exception e) {
            throw new ConfigException(key, "invalid duration: " + raw
                    + " (examples: 30, 500ms, 30s, 5m, 24h, 400d, PT1M)");
        }

    }

    private static List<String> stringList(@Nullable Object value, List<String> def) {

        if (value == null) {
            return def;
        }

        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).toList();
        }

        return List.of(String.valueOf(value).split(","));

    }

}
