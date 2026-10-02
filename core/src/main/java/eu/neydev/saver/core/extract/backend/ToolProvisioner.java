package eu.neydev.saver.core.extract.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The tool self-provisioner - what makes a bare host (a game-server box with nothing but
 * Java) a working media bot: every tool the {@link Toolchain} reports MISSING at startup
 * gets its official static build downloaded into {@code downloader.tools.provision-dir}
 * on a BACKGROUND thread, verified against the release checksum file, and flipped to
 * present by a {@link Toolchain#reprobe()} - no restart, no manual step, no package
 * manager access (the typical bot host has neither root nor a guaranteed distro).
 *
 * <p>Sources, all official release endpoints over HTTPS:
 *
 * <ul>
 *   <li>yt-dlp - GitHub release binary for the detected OS/arch, verified against the
 *       release {@code SHA2-256SUMS};</li>
 *   <li>ffmpeg + ffprobe - ONE BtbN static archive ({@code .tar.xz} on Linux,
 *       {@code .zip} on Windows, ~150 MB once) verified against {@code checksums.sha256},
 *       unpacked in pure Java (commons-compress) down to the two binaries;</li>
 *   <li>gallery-dl - Codeberg release binary (the project left GitHub in 2026; its
 *       GitHub releases carry no assets anymore), verified against {@code SHA256SUMS};
 *       the asset list is resolved through the Codeberg release API because Codeberg has
 *       no {@code latest/download/} alias.</li>
 * </ul>
 *
 * <p>Security posture: a checksum that is missing, unfetchable or mismatching REFUSES the
 * install (fail closed - a bot that silently runs an unverifiable binary is a relay for
 * someone else's build). The downloads run on the host network, deliberately NOT through
 * the SSRF filter proxy: these are operator-trusted release endpoints, while the proxy
 * exists to discipline page-supplied URLs.
 *
 * <p>Every failure degrades exactly like an absent tool: a WARN line, a metric, and the
 * honest TOOL_MISSING answer until the next start or a manual install. Provisioning never
 * throws into the startup path.
 */
public final class ToolProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ToolProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /** The ffmpeg archive is ~150 MB: a slow host line must not truncate the install. */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(20);
    private static final String USER_AGENT = "SaverBot/1.0 (tool-provisioning)";

    private static final String YT_DLP_BASE =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";
    private static final String FFMPEG_BASE =
            "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/";
    private static final String CODEBERG_RELEASE_API =
            "https://codeberg.org/api/v1/repos/mikf/gallery-dl/releases/latest";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public enum Os { LINUX, WINDOWS, UNSUPPORTED }

    public enum Arch { X64, ARM64, UNSUPPORTED }

    /** The matrix the release endpoints actually cover; anything else is skipped. */
    public record Platform(Os os, Arch arch) {

        public static Platform current() {
            return of(System.getProperty("os.name"), System.getProperty("os.arch"));
        }

        public static Platform of(@Nullable String osName, @Nullable String osArch) {

            String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);

            Os os = name.contains("win") ? Os.WINDOWS
                    : name.contains("linux") ? Os.LINUX
                    : Os.UNSUPPORTED;

            Arch ar = arch.equals("amd64") || arch.equals("x86_64") ? Arch.X64
                    : arch.equals("aarch64") || arch.equals("arm64") ? Arch.ARM64
                    : Arch.UNSUPPORTED;

            return new Platform(os, ar);

        }

    }

    /**
     * Where the artifacts come from. Production points at the official release
     * endpoints; tests point at a local server, which is the only reason this seam
     * exists.
     */
    interface ReleaseUrls {

        @Nullable URI ytDlpBinary(String asset);

        @Nullable URI ytDlpChecksums();

        @Nullable URI ffmpegArchive(String asset);

        @Nullable URI ffmpegChecksums();

        @Nullable URI galleryDlBinary(String asset);

        @Nullable URI galleryDlChecksums();

    }

    private static final ReleaseUrls PRODUCTION_URLS = new ReleaseUrls() {

        @Override
        public URI ytDlpBinary(String asset) {
            return URI.create(YT_DLP_BASE + asset);
        }

        @Override
        public URI ytDlpChecksums() {
            return URI.create(YT_DLP_BASE + "SHA2-256SUMS");
        }

        @Override
        public URI ffmpegArchive(String asset) {
            return URI.create(FFMPEG_BASE + asset);
        }

        @Override
        public URI ffmpegChecksums() {
            return URI.create(FFMPEG_BASE + "checksums.sha256");
        }

        @Override
        public @Nullable URI galleryDlBinary(String asset) {
            return codebergUri(asset);
        }

        @Override
        public @Nullable URI galleryDlChecksums() {
            return codebergUri("SHA256SUMS");
        }

    };

    private final AppConfig.Downloader.Tools tools;
    private final MetricsRegistry metrics;
    private final ReleaseUrls urls;
    private final Platform platform;
    private final AtomicBoolean started = new AtomicBoolean();

    public ToolProvisioner(AppConfig.Downloader.Tools tools, MetricsRegistry metrics) {
        this(tools, metrics, PRODUCTION_URLS, Platform.current());
    }

    ToolProvisioner(AppConfig.Downloader.Tools tools, MetricsRegistry metrics,
                    ReleaseUrls urls, Platform platform) {
        this.tools = tools;
        this.metrics = metrics;
        this.urls = urls;
        this.platform = platform;
    }

    /**
     * The tools the live toolchain reports missing, mapped to the path their provisioned
     * binary will occupy. Empty map = nothing to do (the common case in containers).
     */
    public Map<String, String> plan(Toolchain chain) {

        Map<String, String> targets = new LinkedHashMap<>();

        chain.all().forEach((id, state) -> {
            if (!state.present()) {
                targets.put(id, targetPath(id));
            }
        });

        return targets;

    }

    /** Where the provisioned binary of a tool id lives. */
    public String targetPath(String id) {
        return toolsDir().resolve(fileName(id)).toString();
    }

    /**
     * Kicks the install run off on a daemon thread: startup must never WAIT for a
     * 150 MB archive. Jobs arriving before a download lands get the honest TOOL_MISSING;
     * the reprobe after each landed binary flips the state live.
     */
    public void installAsync(Map<String, String> targets, Runnable afterInstall) {

        if (!started.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(() -> install(targets, afterInstall), "tool-provisioner");
        thread.setDaemon(true);
        thread.start();

        log.info("Tool provisioning started in the background for: {}",
                String.join(", ", targets.keySet()));

    }

    void install(Map<String, String> targets, Runnable afterInstall) {

        try {
            Files.createDirectories(toolsDir());
        } catch (IOException e) {
            log.warn("Tool provisioning cannot create {}: {}", toolsDir(), e.getMessage());
            return;
        }

        if (targets.containsKey("ytdlp")) {
            installSingle("ytdlp", targets.get("ytdlp"), afterInstall);
        }

        if (targets.containsKey("ffmpeg") || targets.containsKey("ffprobe")) {
            installFfmpegFamily(targets, afterInstall);
        }

        if (targets.containsKey("gallerydl")) {
            installSingle("gallerydl", targets.get("gallerydl"), afterInstall);
        }

        log.info("Tool provisioning run complete");

    }

    // ---- per-tool installs ---------------------------------------------------------

    private void installSingle(String id, String target, Runnable afterInstall) {

        String asset = "ytdlp".equals(id) ? ytDlpAsset(platform) : galleryDlAsset(platform);

        if (asset == null) {
            skip(id, "no static build for this platform");
            return;
        }

        URI uri = "ytdlp".equals(id) ? urls.ytDlpBinary(asset) : urls.galleryDlBinary(asset);
        URI sums = "ytdlp".equals(id) ? urls.ytDlpChecksums() : urls.galleryDlChecksums();

        if (uri == null) {
            skip(id, "release asset unresolved");
            return;
        }

        fetchAndPlace(id, asset, uri, sums, Path.of(target), afterInstall);

    }

    /** ffmpeg and ffprobe ship in ONE archive: one download serves both ids. */
    private void installFfmpegFamily(Map<String, String> targets, Runnable afterInstall) {

        String asset = ffmpegAsset(platform);

        if (asset == null) {
            skip("ffmpeg", "no static build for this platform");
            skip("ffprobe", "no static build for this platform");
            return;
        }

        URI uri = urls.ffmpegArchive(asset);
        URI sums = urls.ffmpegChecksums();

        if (uri == null) {
            skip("ffmpeg", "release asset unresolved");
            skip("ffprobe", "release asset unresolved");
            return;
        }

        Map<String, Path> wanted = new LinkedHashMap<>();

        for (String id : new String[] {"ffmpeg", "ffprobe"}) {

            String target = targets.get(id);

            if (target != null && !Files.isExecutable(Path.of(target))) {
                wanted.put(id, Path.of(target));
            }

        }

        if (wanted.isEmpty()) {
            skip("ffmpeg", "already present");
            skip("ffprobe", "already present");
            return;
        }

        Path archive = toolsDir().resolve("ffmpeg-archive.part");

        try {

            log.info("Provisioning ffmpeg: downloading {} (one archive serves ffmpeg and ffprobe)", uri);

            long startedAt = System.nanoTime();

            download(uri, archive);

            String expected = checksum(sums, asset);

            if (expected == null || !expected.equalsIgnoreCase(sha256(archive))) {
                fail("ffmpeg", expected == null ? "checksum-unavailable" : "checksum-mismatch", archive);
                fail("ffprobe", expected == null ? "checksum-unavailable" : "checksum-mismatch", null);
                return;
            }

            extractFfmpegArchive(archive, asset.endsWith(".zip"), wanted);

            for (Map.Entry<String, Path> entry : wanted.entrySet()) {

                entry.getValue().toFile().setExecutable(true, true);
                metric(entry.getKey(), "installed");
                log.info("Provisioning {}: installed {} ({} ms)", entry.getKey(), entry.getValue(),
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis());

            }

            afterInstall.run();

        } catch (IOException | RuntimeException e) {

            fail("ffmpeg", "download-failed", archive);
            fail("ffprobe", "download-failed", null);
            log.warn("Provisioning ffmpeg failed: {}", e.getMessage());

        } finally {
            deleteQuietly(archive);
        }

    }

    private void fetchAndPlace(String id, String asset, URI uri, @Nullable URI sums,
                               Path target, Runnable afterInstall) {

        if (Files.isExecutable(target)) {
            skip(id, "already present");
            afterInstall.run();
            return;
        }

        Path part = target.resolveSibling(target.getFileName() + ".part");

        try {

            deleteQuietly(part);
            log.info("Provisioning {}: downloading {}", id, uri);

            long startedAt = System.nanoTime();

            download(uri, part);

            String expected = checksum(sums, asset);

            if (expected == null) {
                fail(id, "checksum-unavailable", part);
                return;
            }

            String actual = sha256(part);

            if (!expected.equalsIgnoreCase(actual)) {
                log.warn("Provisioning {}: checksum mismatch (release says {}, file is {}) - refusing the install",
                        id, expected, actual);
                fail(id, "checksum-mismatch", part);
                return;
            }

            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true, true);

            metric(id, "installed");
            log.info("Provisioning {}: installed {} ({} bytes in {} ms)", id, target,
                    Files.size(target), Duration.ofNanos(System.nanoTime() - startedAt).toMillis());

            afterInstall.run();

        } catch (IOException | RuntimeException e) {
            deleteQuietly(part);
            fail(id, "download-failed", null);
            log.warn("Provisioning {} failed: {}", id, e.getMessage());
        }

    }

    // ---- HTTP, archives, checksums ---------------------------------------------------

    private void download(URI uri, Path destination) throws IOException {

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<Path> response;

        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(destination,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + uri, e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }

    }

    /**
     * The expected SHA-256 of an asset from the release checksum file. Null (refuse) when
     * the file is unfetchable or has no entry for the asset: an unverifiable binary is
     * not worth running.
     */
    private @Nullable String checksum(@Nullable URI sumsUri, String asset) {

        if (sumsUri == null) {
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder(sumsUri)
                .timeout(CONNECT_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        try {

            HttpResponse<String> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                log.warn("Provisioning: checksum file {} answered HTTP {}", sumsUri, response.statusCode());
                return null;
            }

            String expected = parseSha256Sums(response.body()).get(asset);

            if (expected == null) {
                log.warn("Provisioning: checksum file {} has no entry for {}", sumsUri, asset);
            }

            return expected;

        } catch (IOException e) {
            log.warn("Provisioning: cannot fetch checksum file {}: {}", sumsUri, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

    }

    /** {@code sha256sum(1)} format: hash, whitespace, file name (optionally {@code *}). */
    static Map<String, String> parseSha256Sums(String body) {

        Map<String, String> sums = new LinkedHashMap<>();

        for (String line : body.split("\n")) {

            String[] parts = line.trim().split("\\s+");

            if (parts.length >= 2 && parts[0].length() == 64 && isHex(parts[0])) {
                sums.put(parts[parts.length - 1].replaceFirst("^\\*", ""),
                        parts[0].toLowerCase(Locale.ROOT));
            }

        }

        return sums;

    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            int c = Character.toLowerCase(value.charAt(i));
            if (c < '0' || c > '9' && c < 'a' || c > 'f') {
                return false;
            }
        }
        return true;
    }

    /**
     * Unpacks ONLY the two binaries out of the BtbN archive ({@code .../bin/ffmpeg},
     * {@code .../bin/ffprobe}): the archive also carries docs, man pages and GPL text
     * nobody needs in {@code tools/}. Zip entries arrive with forward slashes on every
     * platform, so one suffix match serves both formats.
     */
    private void extractFfmpegArchive(Path archive, boolean zip, Map<String, Path> wanted)
            throws IOException {

        Map<String, Path> byEntry = new LinkedHashMap<>();

        wanted.forEach((id, target) ->
                byEntry.put("bin/" + fileName(id), target));

        try (InputStream file = Files.newInputStream(archive)) {

            if (zip) {

                try (ZipInputStream zipIn = new ZipInputStream(file)) {

                    ZipEntry entry;

                    while ((entry = zipIn.getNextEntry()) != null) {
                        copyIfWanted(zipIn, entry.getName(), byEntry);
                    }

                }

                return;

            }

            try (XZCompressorInputStream xz = new XZCompressorInputStream(file);
                 TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {

                TarArchiveEntry entry;

                while ((entry = tar.getNextEntry()) != null) {
                    copyIfWanted(tar, entry.getName(), byEntry);
                }

            }

        }

    }

    private void copyIfWanted(InputStream in, String entryName, Map<String, Path> byEntry)
            throws IOException {

        for (Map.Entry<String, Path> wanted : byEntry.entrySet()) {

            String suffix = wanted.getKey();

            if (entryName.equals(suffix) || entryName.endsWith("/" + suffix)) {
                Files.copy(in, wanted.getValue(), StandardCopyOption.REPLACE_EXISTING);
                return;
            }

        }

    }

    static String sha256(Path file) throws IOException {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];

            try (InputStream in = Files.newInputStream(file)) {

                int read;

                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }

            }

            return HexFormat.of().formatHex(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }

    }

    // ---- release asset naming ----------------------------------------------------------

    static @Nullable String ytDlpAsset(Platform platform) {

        return switch (platform.os()) {
            case LINUX -> platform.arch() == Arch.X64 ? "yt-dlp_linux"
                    : platform.arch() == Arch.ARM64 ? "yt-dlp_linux_aarch64" : null;
            case WINDOWS -> platform.arch() == Arch.X64 ? "yt-dlp.exe"
                    : platform.arch() == Arch.ARM64 ? "yt-dlp_arm64.exe" : null;
            case UNSUPPORTED -> null;
        };

    }

    static @Nullable String ffmpegAsset(Platform platform) {

        return switch (platform.os()) {
            case LINUX -> platform.arch() == Arch.X64 ? "ffmpeg-master-latest-linux64-gpl.tar.xz"
                    : platform.arch() == Arch.ARM64 ? "ffmpeg-master-latest-linuxarm64-gpl.tar.xz" : null;
            case WINDOWS -> platform.arch() == Arch.X64 ? "ffmpeg-master-latest-win64-gpl.zip" : null;
            case UNSUPPORTED -> null;
        };

    }

    /** gallery-dl publishes standalone binaries for Linux x64 and Windows x64 only. */
    static @Nullable String galleryDlAsset(Platform platform) {

        if (platform.arch() != Arch.X64) {
            return null;
        }

        return switch (platform.os()) {
            case LINUX -> "gallery-dl.bin";
            case WINDOWS -> "gallery-dl.exe";
            case UNSUPPORTED -> null;
        };

    }

    private String fileName(String id) {

        String base = switch (id) {
            case "ytdlp" -> "yt-dlp";
            case "gallerydl" -> "gallery-dl";
            case "ffmpeg" -> "ffmpeg";
            case "ffprobe" -> "ffprobe";
            default -> id;
        };

        return platform.os() == Os.WINDOWS ? base + ".exe" : base;

    }

    private Path toolsDir() {
        return Path.of(tools.provisionDir().isBlank() ? "tools" : tools.provisionDir().trim());
    }

    // ---- outcomes -----------------------------------------------------------------------

    private void skip(String id, String reason) {
        metric(id, "skipped");
        log.info("Provisioning {}: skipped - {} (platform {}/{}), the backend stays disabled",
                id, reason, platform.os(), platform.arch());
    }

    private void fail(String id, String result, @Nullable Path leftover) {
        deleteQuietly(leftover);
        metric(id, result);
    }

    private void metric(String tool, String result) {
        metrics.increment("tools_provision_total", "tool", tool, "result", result);
    }

    private static void deleteQuietly(@Nullable Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A leftover .part is overwritten by the next attempt; nothing to do.
            }
        }
    }

    // ---- Codeberg release resolution ------------------------------------------------------

    private static volatile Map<String, String> codebergAssets;

    private static @Nullable URI codebergUri(String asset) {
        String url = codebergAssets().get(asset);
        return url == null ? null : URI.create(url);
    }

    /**
     * Codeberg has no {@code releases/latest/download/} alias, so the asset URLs come
     * from the release API once per process. A failure yields an empty map: gallery-dl
     * stays MISSING, which is exactly today's behaviour on a bare host.
     */
    static Map<String, String> codebergAssets() {

        Map<String, String> cached = codebergAssets;

        if (cached != null) {
            return cached;
        }

        synchronized (ToolProvisioner.class) {

            if (codebergAssets != null) {
                return codebergAssets;
            }

            Map<String, String> fetched = Map.of();

            HttpRequest request = HttpRequest.newBuilder(URI.create(CODEBERG_RELEASE_API))
                    .timeout(CONNECT_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            try {

                HttpResponse<String> response =
                        HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() / 100 == 2) {
                    fetched = parseReleaseAssets(response.body());
                } else {
                    log.warn("Provisioning: Codeberg release API answered HTTP {}", response.statusCode());
                }

            } catch (IOException e) {
                log.warn("Provisioning: cannot reach the Codeberg release API: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            codebergAssets = fetched;
            return fetched;

        }

    }

    /** name -&gt; browser_download_url over the {@code assets} array of a release JSON. */
    static Map<String, String> parseReleaseAssets(String json) {

        Map<String, String> assets = new LinkedHashMap<>();

        try {

            JsonNode list = MAPPER.readTree(json).path("assets");

            for (JsonNode node : list) {

                String name = node.path("name").asText("");
                String url = node.path("browser_download_url").asText("");

                if (!name.isEmpty() && !url.isEmpty()) {
                    assets.put(name, url);
                }

            }

        } catch (IOException | RuntimeException e) {
            log.warn("Provisioning: cannot parse the release asset list: {}", e.getMessage());
        }

        return assets;

    }

    /** Test seam: the Codeberg cache is process-global, tests reset it. */
    static void resetCodebergCache() {
        codebergAssets = null;
    }

}
