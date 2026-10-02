package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.util.ProcessRunner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locates and fingerprints the external extraction tools. The bot does not bundle them -
 * yt-dlp and gallery-dl are fast-moving Python projects whose whole value is being
 * current, and the Docker image installs pinned versions at build time. What the
 * Toolchain adds:
 *
 * <ul>
 *   <li>resolution: configured path, or a name found on PATH (POSIX scan; Windows
 *       resolves through ProcessBuilder as usual);</li>
 *   <li>probing: one {@code --version} run per tool, versions land in the log, /healthz
 *       and the admin panel - "which yt-dlp is this" is the first question of every
 *       extraction bug report;</li>
 *   <li>RE-probing: {@link #reprobe()} re-runs the fingerprints after a tool update, so
 *       /healthz never lies about the version actually on disk;</li>
 *   <li>degradation: a missing tool disables only its backend, never the bot;</li>
 *   <li>self-provisioning hook: {@link #redirect(Map)} re-points a missing tool at the
 *       path where {@link ToolProvisioner} is about to place it, so the re-probe after
 *       the download flips the tool to present without a restart.</li>
 * </ul>
 */
public final class Toolchain {

    private static final Logger log = LoggerFactory.getLogger(Toolchain.class);

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * @param name           tool id ("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe");
     * @param configured     what the config says (a path or a bare name);
     * @param resolvedPath   absolute path when the configured value is one, else null
     *                       (a bare name is resolved by ProcessBuilder via PATH);
     * @param present        the version probe succeeded;
     * @param version        first line of {@code --version}, trimmed.
     */
    public record ToolState(String name, String configured, @Nullable String resolvedPath,
                            boolean present, @Nullable String version) {

        /** The command to put on a process command line. */
        public String command() {
            return resolvedPath != null ? resolvedPath : configured;
        }

    }

    private volatile String ytDlpPath;
    private volatile String galleryDlPath;
    private volatile String ffmpegPath;
    private volatile String ffprobePath;

    private volatile Map<String, ToolState> tools;

    private Toolchain(String ytDlp, String galleryDl, String ffmpeg, String ffprobe) {
        this.ytDlpPath = ytDlp;
        this.galleryDlPath = galleryDl;
        this.ffmpegPath = ffmpeg;
        this.ffprobePath = ffprobe;
        this.tools = probeAll();
    }

    /** Probes every configured tool. Never throws: absence is a state, not an error. */
    public static Toolchain probe(String ytDlp, String galleryDl, String ffmpeg) {
        return probe(ytDlp, galleryDl, ffmpeg, "ffprobe");
    }

    public static Toolchain probe(String ytDlp, String galleryDl, String ffmpeg, String ffprobe) {

        Toolchain toolchain = new Toolchain(
                blankTo(ytDlp, "yt-dlp"), blankTo(galleryDl, "gallery-dl"),
                blankTo(ffmpeg, "ffmpeg"), blankTo(ffprobe, "ffprobe"));

        log.info("Toolchain: {}", toolchain.describe());

        return toolchain;

    }

    private static String blankTo(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private Map<String, ToolState> probeAll() {

        Map<String, ToolState> states = new LinkedHashMap<>();
        states.put("ytdlp", probeTool("yt-dlp", ytDlpPath));
        states.put("gallerydl", probeTool("gallery-dl", galleryDlPath));
        states.put("ffmpeg", probeTool("ffmpeg", ffmpegPath));
        states.put("ffprobe", probeTool("ffprobe", ffprobePath));

        return Map.copyOf(states);

    }

    /**
     * Re-fingerprints every tool and swaps the snapshot atomically. Called by the
     * {@link ToolUpdater} after a self-update run and by the {@link ToolProvisioner}
     * after each landed download; readers (healthz, extractors) see either the old or
     * the new consistent state, never a half-updated map.
     */
    public synchronized void reprobe() {
        rescan();
    }

    /**
     * Re-points tool ids at new paths (the self-provisioner's target directory) and
     * re-probes in one atomic step: a tool whose download has already landed flips to
     * present immediately, one still in flight stays MISSING until its reprobe.
     */
    public synchronized void redirect(Map<String, String> targets) {

        if (targets.containsKey("ytdlp")) {
            ytDlpPath = targets.get("ytdlp");
        }
        if (targets.containsKey("gallerydl")) {
            galleryDlPath = targets.get("gallerydl");
        }
        if (targets.containsKey("ffmpeg")) {
            ffmpegPath = targets.get("ffmpeg");
        }
        if (targets.containsKey("ffprobe")) {
            ffprobePath = targets.get("ffprobe");
        }

        log.info("Toolchain: redirected {} to the provision dir - waiting for the downloads",
                String.join(", ", targets.keySet()));

        rescan();

    }

    private void rescan() {

        Map<String, ToolState> before = tools;
        Map<String, ToolState> after = probeAll();
        tools = after;

        after.forEach((id, state) -> {

            String oldVersion = before.get(id) != null ? before.get(id).version() : null;

            if (state.present() && !java.util.Objects.equals(oldVersion, state.version())) {
                log.info("Toolchain: {} updated {} -> {}", id, oldVersion, state.version());
            }

        });

    }

    private static ToolState probeTool(String name, String configured) {

        String resolvedPath = resolvePath(configured);
        String command = resolvedPath != null ? resolvedPath : configured;

        // ffprobe/ffmpeg answer "-version", yt-dlp/gallery-dl answer "--version".
        ProcessRunner.Result result = new ProcessRunner(PROBE_TIMEOUT)
                .run(List.of(command, "-version"), null);

        if (!result.success()) {

            result = new ProcessRunner(PROBE_TIMEOUT)
                    .run(List.of(command, "--version"), null);

        }

        if (result.success() && !result.stdoutTail().isEmpty()) {

            String version = result.stdoutTail().get(0).trim();
            return new ToolState(name, configured, resolvedPath, true, version);

        }

        return new ToolState(name, configured, resolvedPath, false, null);

    }

    /** Absolute when the configured value points at an executable file, null otherwise. */
    private static @Nullable String resolvePath(String value) {

        if (value.contains("/") || value.contains(File.separator)) {

            Path path = Path.of(value);

            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }

            // A configured path that does not exist is still passed through: the probe
            // fails visibly with the operator's own value in the log.
            return value;

        }

        // Bare name: find it on PATH so the log shows WHICH binary answered.
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {

            Path candidate = Path.of(dir, value);
            Path windowsCandidate = Path.of(dir, value + ".exe");

            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().toString();
            }

            if (Files.isExecutable(windowsCandidate)) {
                return windowsCandidate.toAbsolutePath().toString();
            }

        }

        return null;

    }

    public ToolState ytDlp() {
        return tools.get("ytdlp");
    }

    public ToolState galleryDl() {
        return tools.get("gallerydl");
    }

    public ToolState ffmpeg() {
        return tools.get("ffmpeg");
    }

    public ToolState ffprobe() {
        return tools.get("ffprobe");
    }

    public boolean hasYtDlp() {
        return ytDlp().present();
    }

    public boolean hasGalleryDl() {
        return galleryDl().present();
    }

    public boolean hasFfmpeg() {
        return ffmpeg().present();
    }

    /** ffprobe fills in duration/dimensions the page metadata did not know (D26). */
    public boolean hasFfprobe() {
        return ffprobe().present();
    }

    public Map<String, ToolState> all() {
        return tools;
    }

    /** One line for logs, /healthz and the admin panel. */
    public String describe() {

        StringBuilder sb = new StringBuilder();

        tools.forEach((id, state) -> {

            if (!sb.isEmpty()) {
                sb.append(", ");
            }

            sb.append(id).append('=');

            if (state.present()) {
                String version = state.version() == null ? "present" : state.version();
                sb.append(version.length() > 40 ? version.substring(0, 40) : version);
            } else {
                sb.append("MISSING");
            }

        });

        return sb.toString();

    }

}
