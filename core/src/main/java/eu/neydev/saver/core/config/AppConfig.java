package eu.neydev.saver.core.config;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.util.ByteFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fully typed application configuration. Immutable: hot edits are performed by a
 * restart (message bundles and the source catalog are re-read hot in the process).
 *
 * <p>Secrets are NOT stored in the file: values like {@code ${TELEGRAM_BOT_TOKEN:}}
 * are substituted from environment variables (or config/.env) at load time.
 */
public record AppConfig(@NotNull Storage storage,
                        @NotNull Downloader downloader,
                        @NotNull Limits limits,
                        @NotNull Delivery delivery,
                        @NotNull Pipeline pipeline,
                        @NotNull WebApp webApp,
                        @NotNull Locale locale,
                        @NotNull Sources sources,
                        @NotNull Community community,
                        @NotNull Map<Platform, PlatformSection> platforms,
                        @NotNull List<String> ownerKeys,
                        @NotNull Status status) {

    public AppConfig {
        platforms = Map.copyOf(platforms);
        ownerKeys = List.copyOf(ownerKeys);
    }

    /** Platform section; for those not specified in the config - guaranteed disabled. */
    public PlatformSection platform(Platform platform) {
        return platforms.getOrDefault(platform, PlatformSection.DISABLED);
    }

    /** Storage for users, the job log and usage counters. */
    public record Storage(@NotNull Type type,
                          @NotNull String sqlitePath,
                          @Nullable String jdbcUrl,
                          @Nullable String username,
                          @Nullable String password,
                          int poolSize) {
        public enum Type { SQLITE, POSTGRES }
    }

    /**
     * The download engine: external tools, concurrency, per-request caps and the disk
     * vault. {@code maxFileBytes} is the global ceiling; the effective per-job cap is
     * {@code min(maxFileBytes, platform delivery cap)} so yt-dlp picks a format that
     * actually fits the target platform instead of downloading a 2 GB "best".
     */
    public record Downloader(int workers,
                             int queueCapacity,
                             int perUserConcurrent,
                             int maxItemsPerRequest,
                             Duration extractTimeout,
                             Duration downloadTimeout,
                             long maxFileBytes,
                             QualityPreset defaultQuality,
                             boolean allowPrivateNetworks,
                             @Nullable String proxy,
                             @Nullable String cookiesFile,
                             boolean cookiesOwnerOnly,
                             double perSourcePerSecond,
                             Duration sleepRequests,
                             Duration jobLogRetention,
                             Duration shutdownDrain,
                             boolean writeSubs,
                             boolean audioThumbnail,
                             @NotNull List<String> blockedExtensions,
                             @NotNull Tools tools,
                             @NotNull Vault vault) {

        public Downloader {
            blockedExtensions = List.copyOf(blockedExtensions);
        }

        /**
         * Executable/script/mountable extensions never delivered as files (the
         * malware-channel guard). A blocklist is by definition a trailing defense -
         * it stops the KNOWN-dangerous types; the per-user quota and the source
         * catalog bound the rest.
         */
        public static final List<String> DEFAULT_BLOCKED_EXTENSIONS = List.of(
                // Windows executables and installers
                "exe", "dll", "msi", "msix", "appx", "com", "scr", "pif", "cpl", "msc",
                "gadget", "application",
                // Scripts (Windows + POSIX)
                "bat", "cmd", "vbs", "vbe", "js", "jse", "wsf", "wsh", "ps1", "psm1",
                "hta", "sh", "jar", "scf", "reg", "inf",
                // Shortcut/redirect formats used by droppers
                "lnk", "url",
                // Mountable images with autorun history
                "iso", "img",
                // Macro-enabled office documents
                "docm", "xlsm", "pptm", "xlam");

        /** External extraction tools: paths (or bare names resolved via PATH). */
        public record Tools(@NotNull String ytDlp,
                            @NotNull String galleryDl,
                            @NotNull String ffmpeg,
                            @NotNull String ffprobe,
                            Duration updateInterval) {

            public static final Tools DEFAULT = new Tools("yt-dlp", "gallery-dl", "ffmpeg",
                    "ffprobe", Duration.ZERO);

            /** Zero means "never auto-update"; the tools belong to the image/package manager. */
            public boolean autoUpdates() {
                return updateInterval != null && !updateInterval.isZero()
                        && !updateInterval.isNegative();
            }

        }

        /** Where downloaded files live between the extractor and the platform send. */
        public record Vault(@NotNull String dir,
                            long maxBytes,
                            Duration ttl,
                            Duration sendGrace) {

            public static final Vault DEFAULT = new Vault("data/media",
                    10L * 1000 * 1000 * 1000, Duration.ofMinutes(30), Duration.ofMinutes(10));

        }

    }

    /** Anti-abuse quotas. Downloading is expensive; a public bot without quotas is a DDoS relay. */
    public record Limits(int jobsPerUserPerHour,
                         int jobsPerUserPerDay,
                         long bytesPerUserPerDay) {

        public static final Limits DEFAULT = new Limits(20, 200,
                ByteFormat.parse("2gb", "limits.mb-per-user-per-day"));

    }

    /**
     * Per-platform media caps (bytes) and the size-overflow policy. The caps mirror the
     * published Bot API limits; the config exists because they change and because bot
     * tiers differ (Discord boosts, Telegram local API server with a 2 GB cap...).
     */
    public record Delivery(@NotNull Map<Platform, SizeCaps> caps, boolean linkFallback) {

        public Delivery {
            // Not "new EnumMap<>(caps)": an empty map gives EnumMap no key type to
            // infer and it throws. Copy entry by entry instead.
            EnumMap<Platform, SizeCaps> copy = new EnumMap<>(Platform.class);
            copy.putAll(caps);
            caps = java.util.Collections.unmodifiableMap(copy);
        }

        public record SizeCaps(long photo, long video, long audio, long gif, long document) {

            public static SizeCaps uniform(long bytes) {
                return new SizeCaps(bytes, bytes, bytes, bytes, bytes);
            }

        }

        public SizeCaps capsFor(Platform platform) {
            // Absent platforms get a conservative 8 MB chat-API-ish default. Bukkit is
            // the exception: it "delivers" by copying files to the server's own disk,
            // so its unconfigured cap matches the parseCaps default (2 GB), not 8 MB.
            SizeCaps fallback = platform == Platform.BUKKIT
                    ? SizeCaps.uniform(2_048L * 1024 * 1024)
                    : SizeCaps.uniform(8_000_000);
            return caps.getOrDefault(platform, fallback);
        }

    }

    /** Inbound/outbound message pipelines and rate limiting. */
    public record Pipeline(int inboundQueueCapacity,
                           int workerThreads,
                           int inboundPerUserPerWindow,
                           int outboundWorkersPerPlatform,
                           double outboundGlobalPerSecond,
                           int outboundPerChatPerMinute,
                           Duration sendTimeout) {
    }

    /** The service HTTP server: /healthz, /metrics, media links and platform webhooks. */
    public record WebApp(boolean enabled,
                         @NotNull String bindHost,
                         int port,
                         @Nullable String publicUrl,
                         Duration mediaLinkTtl,
                         @Nullable String metricsToken) {

        /** Every call site asked the negative, so the config speaks the negative. */
        public boolean metricsTokenMissing() {
            return metricsToken == null || metricsToken.isBlank();
        }

        /**
         * A non-loopback bind address exposes {@code /metrics} and {@code /healthz} to the
         * network; without a token anyone reaching the port can read the counters.
         */
        public boolean bindsPublicly() {
            return !bindHost.isBlank() && !bindHost.equals("127.0.0.1")
                    && !bindHost.equals("localhost") && !bindHost.equals("::1");
        }

    }

    /**
     * Languages. The iteration order of {@code supported} is preserved and defines
     * the button order in the language picker; {@code displayNames} maps a language
     * code to its human-readable name (each language in its own script).
     */
    public record Locale(@NotNull String defaultLanguage,
                         @NotNull Set<String> supported,
                         @NotNull Map<String, String> displayNames) {

        public Locale {
            supported = Collections.unmodifiableSet(new LinkedHashSet<>(supported));
            displayNames = Map.copyOf(displayNames);
        }

        public Locale(@NotNull String defaultLanguage, @NotNull Set<String> supported) {
            this(defaultLanguage, supported, Map.of());
        }

    }

    /** Source catalog overrides from the configuration. */
    public record Sources(@NotNull List<String> disabled) {

        public Sources {
            disabled = List.copyOf(disabled);
        }

    }

    /** Public community: repository link in /about. Empty - the block is hidden. */
    public record Community(@Nullable String githubUrl) {

        public boolean hasGithub() {
            return githubUrl != null && !githubUrl.isBlank();
        }

    }

    /**
     * Operational visibility without an HTTP port: one INFO line with the key counters
     * every {@code heartbeatInterval}, plus a liveness file touched on the same beat.
     * A container HEALTHCHECK looks at the file's mtime.
     */
    public record Status(Duration heartbeatInterval, @Nullable String livenessFile) {

        public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofMinutes(5);
        public static final String DEFAULT_LIVENESS_FILE = "data/bot.liveness";
        public static final Status DEFAULT =
                new Status(DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_LIVENESS_FILE);

    }

    /** Platform settings: token from the environment + platform details. */
    public record PlatformSection(boolean enabled,
                                  @Nullable String token,
                                  @Nullable String extraId,
                                  @Nullable String secret,
                                  @Nullable String proxy) {

        public static final PlatformSection DISABLED =
                new PlatformSection(false, null, null, null, null);

        /** Enabled AND has the credential - the check every platform module runs. */
        public boolean isActive() {
            return enabled && token != null && !token.isBlank();
        }

    }

}
