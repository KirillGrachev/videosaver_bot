package eu.neydev.saver.core.extract.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.Extractor;
import eu.neydev.saver.core.extract.ProgressListener;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.util.ProcessRunner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * yt-dlp backend: the workhorse for ~1000 video sites. Two phases per request:
 *
 * <ol>
 *   <li>PROBE - one CHEAP metadata pass ({@code --flat-playlist --no-download --print}):
 *       playlist entries are listed without resolving their formats, so a 500-entry
 *       playlist costs one page fetch, not 500. The probe catches private/age/geo/login
 *       refusals BEFORE a download starts and detects live streams (recording one would
 *       run until the timeout);</li>
 *   <li>DOWNLOAD into the job-exclusive work dir with a format chosen from the quality
 *       preset, {@code --max-filesize} for direct formats AND a live size watchdog for
 *       segmented ones (HLS/DASH ignore max-filesize - the watchdog kills the process
 *       the moment a file crosses the cap, turning a wasted 2 GB download into an
 *       honest TOO_LARGE), ffmpeg merging/remuxing when present, {@code --newline}
 *       progress into the {@link ProgressListener}.</li>
 * </ol>
 *
 * <p>The result is read by SCANNING the work dir (it belongs to this job alone), and
 * per-file metadata is joined back by video id from the probe.
 *
 * <p>Error classification is an ORDERED PATTERN TABLE over the tool's own ERROR lines:
 * the first match wins, actionable categories (login/age/private/geo) are checked
 * before generic ones, and every UNKNOWN classification increments
 * {@code extract_classify_unknown_total} - when yt-dlp rewords its errors after an
 * update, the metric moves before users start complaining.
 */
public final class YtDlpExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(YtDlpExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern PERCENT = Pattern.compile("\\[download]\\s+(\\d{1,3}(?:\\.\\d+)?)%");
    private static final Pattern DESTINATION = Pattern.compile("\\[download] Destination: (.+)");

    /**
     * Probe output: one TSV line per entry (a single video is a one-entry "playlist").
     * The title is the LAST field and the split is bounded: yt-dlp does not escape tabs
     * inside titles, and a tab in the title must corrupt nothing but itself.
     */
    private static final String PROBE_TEMPLATE =
            "%(id)s\t%(duration)s\t%(width)s\t%(height)s\t%(is_live)s\t%(webpage_url)s\t%(uploader)s\t%(title)s";

    public static final String BACKEND_ID = "ytdlp";

    /** The watchdog fires at cap + 15%: fragment overhead must not false-positive. */
    private static final double WATCHDOG_MARGIN = 1.15;

    /**
     * Operator-controlled knobs; all optional. {@code proxy} is also how the tools reach
     * blocked networks; {@code cookiesFile} unlocks login-walled content - see the
     * README disclaimer and {@code downloader.cookies-owner-only}.
     */
    public record Options(@Nullable String proxy,
                          @Nullable String cookiesFile,
                          Duration extractTimeout,
                          Duration downloadTimeout,
                          int concurrentFragments,
                          Duration sleepRequests,
                          boolean writeSubs,
                          boolean audioThumbnail) {

        public static Options defaults() {
            return new Options(null, null,
                    Duration.ofMinutes(2), Duration.ofMinutes(10), 4,
                    Duration.ZERO, false, false);
        }

    }

    /** One probe line. NA fields arrive as "NA" and become nulls; {@code live} is
        tri-state because flat probes often cannot tell (null) - the JSON fallback resolves it. */
    private record Meta(String id, @Nullable String title, @Nullable Integer duration,
                        @Nullable Integer width, @Nullable Integer height, @Nullable Boolean live,
                        @Nullable String webpageUrl, @Nullable String uploader) {

        static Meta parse(String line) {

            String[] parts = line.split("\t", 8);

            return new Meta(
                    at(parts, 0),
                    at(parts, 7).isEmpty() ? null : at(parts, 7),
                    intOrNull(at(parts, 1)),
                    intOrNull(at(parts, 2)),
                    intOrNull(at(parts, 3)),
                    boolOrNull(at(parts, 4)),
                    naToNull(at(parts, 5)),
                    naToNull(at(parts, 6)));

        }

        private static @Nullable Boolean boolOrNull(String value) {

            String clean = naToNull(value);

            if (clean == null) {
                return null;
            }

            return "true".equalsIgnoreCase(clean) || "1".equals(clean);

        }

        private static String at(String[] parts, int index) {
            return index < parts.length ? parts[index].trim() : "";
        }

        private static @Nullable String naToNull(String value) {
            return value == null || value.isEmpty() || "NA".equals(value) ? null : value;
        }

        private static @Nullable Integer intOrNull(String value) {

            String clean = naToNull(value);

            if (clean == null) {
                return null;
            }

            try {
                return (int) Double.parseDouble(clean.contains(".")
                        ? clean.substring(0, clean.indexOf('.'))
                        : clean);
            } catch (NumberFormatException e) {
                return null;
            }

        }

    }

    /**
     * Ordered classification table: (regex over the tool output, category). Patterns are
     * taken from real yt-dlp ERROR strings (2023-2026 wording, incl. "Sign in to confirm
     * you're not a bot"); first match wins, so actionable reasons are listed first.
     */
    private static final List<Map.Entry<Pattern, Category>> CLASSIFIERS = List.of(
            // AGE goes FIRST: yt-dlp's "Sign in to confirm your age" contains "sign in"
            // but the actionable reason is the age gate, not the login form.
            Map.entry(Pattern.compile("age[ -]?restrict|confirm your age",
                    Pattern.CASE_INSENSITIVE), Category.AGE_RESTRICTED),
            Map.entry(Pattern.compile("private (video|playlist|post)|(video|post) is private|"
                    + "granted access", Pattern.CASE_INSENSITIVE), Category.PRIVATE),
            // "cookies" alone is NOT a login signal: an operator's corrupt cookies file
            // ("could not parse cookies") is a config problem, not a login wall. Only the
            // phrasings yt-dlp uses for actual account walls are matched.
            Map.entry(Pattern.compile("sign in to confirm|log ?in|authentication|"
                    + "only available (for|to)|premium|account required|with cookies|"
                    + "cookies from browser|use cookies|pass cookies", Pattern.CASE_INSENSITIVE),
                    Category.LOGIN_REQUIRED),
            Map.entry(Pattern.compile("in your country|geo.?blocked|not available in",
                    Pattern.CASE_INSENSITIVE), Category.GEO_BLOCKED),
            Map.entry(Pattern.compile("429|too many requests|rate.?limit|throttl",
                    Pattern.CASE_INSENSITIVE), Category.RATE_LIMITED),
            Map.entry(Pattern.compile("max-?filesize|larger than", Pattern.CASE_INSENSITIVE),
                    Category.TOO_LARGE),
            Map.entry(Pattern.compile("is a live|livestream", Pattern.CASE_INSENSITIVE),
                    Category.LIVE_STREAM),
            Map.entry(Pattern.compile("unsupported url|no video formats|requested format is not available",
                    Pattern.CASE_INSENSITIVE), Category.UNSUPPORTED),
            // "Video unavailable" is NOT a 404: the page answers 200, the media behind
            // it is gone (deleted, hidden by the author, switched to embed-only).
            Map.entry(Pattern.compile("(video|media|stream|clip) is unavailable|video unavailable|"
                    + "removed by the uploader", Pattern.CASE_INSENSITIVE), Category.UNAVAILABLE),
            Map.entry(Pattern.compile("404|not found|does not exist|removed|unavailable|no such",
                    Pattern.CASE_INSENSITIVE), Category.NOT_FOUND),
            Map.entry(Pattern.compile("unable to download|network|connection|timed out|ssl|"
                    + "temporary failure|http error 5|fragment \\d+ not found",
                    Pattern.CASE_INSENSITIVE), Category.NETWORK));

    private final Toolchain tools;
    private final Options options;
    private final @Nullable MetricsRegistry metrics;

    public YtDlpExtractor(Toolchain tools, Options options) {
        this(tools, options, null);
    }

    public YtDlpExtractor(Toolchain tools, Options options, @Nullable MetricsRegistry metrics) {
        this.tools = tools;
        this.options = options;
        this.metrics = metrics;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public boolean available() {
        return tools.hasYtDlp();
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) throws ExtractionException {

        String url = request.url().toString();

        List<Meta> metas = probe(url, request);

        if (metas.stream().anyMatch(meta -> Boolean.TRUE.equals(meta.live()))) {
            throw new ExtractionException(Category.LIVE_STREAM, BACKEND_ID,
                    "Live streams are not recorded: " + url);
        }

        boolean playlist = metas.size() > 1;
        boolean truncated = playlist && metas.size() >= request.maxItems();

        Path workDir = request.workDir();
        List<String> command = downloadCommand(request, playlist);

        log.debug("yt-dlp download: {} (playlist={}, quality={}, entries={})",
                url, playlist, request.quality(), metas.size());

        // The watchdog bounds the WHOLE work dir, so a legitimate N-entry playlist
        // needs N times the per-file cap - otherwise a 10-video album dies as TOO_LARGE.
        long watchdogCap = (long) (request.maxFileBytes() * WATCHDOG_MARGIN
                * Math.max(1, metas.size()));

        ProcessRunner.Result result = new ProcessRunner(options.downloadTimeout())
                .run(command, workDir, progressLines(request.progress()),
                        () -> request.progress().cancelled() || sizeExceeded(workDir, watchdogCap));

        if (result.cancelled() || request.progress().cancelled()) {
            throw new ExtractionException(Category.CANCELLED, BACKEND_ID, "Download cancelled");
        }

        if (result.aborted()) {

            if (sizeExceeded(workDir, watchdogCap)) {
                throw new ExtractionException(Category.TOO_LARGE, BACKEND_ID,
                        "Stream exceeded the " + request.maxFileBytes() + " byte cap mid-download "
                                + "(segmented formats ignore --max-filesize; the watchdog stopped it)");
            }

            throw new ExtractionException(Category.CANCELLED, BACKEND_ID, "Download aborted");

        }

        if (result.timedOut()) {
            throw new ExtractionException(Category.TIMEOUT, BACKEND_ID,
                    "Download timed out after " + options.downloadTimeout().toMinutes() + " min");
        }

        if (!result.success()) {
            throw classify(result, url);
        }

        List<ExtractedItem> items = scanWorkDir(workDir, metas, request);

        if (items.isEmpty()) {
            throw new ExtractionException(Category.UNKNOWN, BACKEND_ID,
                    "yt-dlp finished without producing files");
        }

        String warningKey = request.quality() == QualityPreset.AUDIO && !tools.hasFfmpeg()
                ? "message.warning.audio_raw"
                : null;

        Meta first = metas.isEmpty() ? null : metas.get(0);

        return new ExtractionResult(request.source(), BACKEND_ID, items,
                playlist ? null : first != null ? first.title() : null,
                playlist ? null : first != null ? first.uploader() : null,
                playlist ? url : first != null && first.webpageUrl() != null ? first.webpageUrl() : url,
                truncated, warningKey);

    }

    // ---- phase 1: cheap flat probe -----------------------------------------------

    private List<Meta> probe(String url, ExtractionRequest request) throws ExtractionException {
        // (metas is reassigned when the JSON fallback resolves an unknown live-state)

        List<String> command = new ArrayList<>(List.of(
                tools.ytDlp().command(),
                "--no-download", "--no-warnings", "--flat-playlist",
                "--playlist-items", "1-" + request.maxItems(),
                "--print", PROBE_TEMPLATE));
        command.add(url);
        appendCommon(command, request);

        ProcessRunner.Result result = new ProcessRunner(options.extractTimeout())
                .run(command, null);

        if (result.cancelled()) {
            throw new ExtractionException(Category.CANCELLED, BACKEND_ID, "Cancelled");
        }

        if (result.timedOut()) {
            throw new ExtractionException(Category.TIMEOUT, BACKEND_ID, "Metadata probe timed out");
        }

        if (!result.success()) {
            throw classify(result, url);
        }

        List<Meta> metas = new ArrayList<>();

        for (String line : result.stdoutTail()) {   // TSV lines are short; the tail is enough

            String trimmed = line.trim();

            // --print writes bare TSV lines; anything with a tab and a non-empty id is meta
            if (trimmed.contains("\t") && !trimmed.startsWith("[") && !trimmed.startsWith("WARNING")) {
                metas.add(Meta.parse(trimmed));
            }

        }

        if (metas.isEmpty()) {

            // Some extractors answer flat mode with nothing useful; a JSON fallback
            // keeps the probe honest without paying for full format resolution twice.
            Meta single = probeJsonFallback(url, request);

            if (single == null) {
                throw new ExtractionException(Category.UNKNOWN, BACKEND_ID,
                        "yt-dlp probe returned no metadata");
            }

            metas.add(single);

        } else if (metas.size() == 1 && metas.get(0).live() == null) {

            // A flat probe often cannot tell live from VOD for a single video. Recording
            // a live stream means burning the whole download timeout on a partial file,
            // so an UNKNOWN live-state is resolved through the (now full-capture) JSON
            // probe before committing to the download.
            Meta resolved = probeJsonFallback(url, request);

            if (resolved != null) {
                metas = List.of(resolved);
            }

        }

        return metas;

    }

    /**
     * Full-info fallback probe. The JSON of a real video runs to thousands of lines,
     * far past the runner's 200-line tail - so the document is assembled from the LIVE
     * line consumer, not from the tail.
     */
    private @Nullable Meta probeJsonFallback(String url, ExtractionRequest request) {

        List<String> command = new ArrayList<>(List.of(
                tools.ytDlp().command(),
                "-J", "--no-download", "--no-warnings", "--no-playlist"));
        command.add(url);
        appendCommon(command, request);

        StringBuilder full = new StringBuilder(1 << 16);

        ProcessRunner.Result result = new ProcessRunner(options.extractTimeout())
                .run(command, null, line -> full.append(line).append('\n'));

        if (!result.success()) {
            return null;
        }

        try {

            JsonNode info = MAPPER.readTree(full.toString().trim());
            JsonNode video = info.has("entries") && info.path("entries").isArray()
                    && info.path("entries").size() > 0
                    ? info.path("entries").get(0)
                    : info;

            JsonNode liveNode = video.path("is_live");

            return new Meta(video.path("id").asText(""),
                    naToNull(video.path("title").asText(null)),
                    positiveOrNull(video.path("duration")),
                    positiveOrNull(video.path("width")),
                    positiveOrNull(video.path("height")),
                    liveNode.isBoolean() ? liveNode.asBoolean() : null,
                    naToNull(video.path("webpage_url").asText(null)),
                    naToNull(video.path("uploader").asText(null)));

        } catch (IOException | RuntimeException e) {
            return null;
        }

    }

    private static @Nullable String naToNull(@Nullable String value) {
        return value == null || value.isBlank() || "NA".equals(value) ? null : value;
    }

    private static @Nullable Integer positiveOrNull(JsonNode node) {

        if (node.isMissingNode() || node.isNull() || !node.canConvertToInt()) {
            return null;
        }

        int value = node.asInt();

        return value > 0 ? value : null;

    }

    // ---- phase 2: download ----------------------------------------------------------

    private List<String> downloadCommand(ExtractionRequest request, boolean playlist) {

        List<String> command = new ArrayList<>();
        command.add(tools.ytDlp().command());
        command.add("--newline");
        command.add("--no-warnings");
        command.add("-P");
        command.add(request.workDir().toString());
        // A collision would mean the same video id twice in one job dir - the same
        // content, so an overwrite loses nothing; the scan dedupes by definition.
        command.add("-o");
        command.add("%(id).80s.%(ext)s");
        command.add("--restrict-filenames");
        command.add("--no-write-infojson");
        command.add("--no-write-comments");

        if (playlist) {
            command.add("--yes-playlist");
            command.add("--playlist-items");
            command.add("1-" + request.maxItems());
        } else {
            command.add("--no-playlist");
        }

        boolean ffmpeg = tools.hasFfmpeg();
        boolean audio = request.quality() == QualityPreset.AUDIO;

        if (audio) {

            command.add("-f");
            command.add("bestaudio/audio");

            if (ffmpeg) {
                command.add("-x");
                command.add("--audio-format");
                command.add("mp3");
            }

            if (options.audioThumbnail()) {
                command.add("--write-thumbnail");
            }

        } else {

            switch (request.quality()) {

                case BEST -> command.addAll(ffmpeg
                        ? List.of("-f", "bestvideo+bestaudio/best",
                                "--merge-output-format", "mp4", "--remux-video", "mp4")
                        : List.of("-f", "best"));

                case P1080, P720, P480 -> {

                    int height = switch (request.quality()) {
                        case P1080 -> 1080;
                        case P720 -> 720;
                        default -> 480;
                    };

                    command.add("-f");
                    command.add(ffmpeg
                            ? "bestvideo[height<=%d]+bestaudio/best[height<=%d]/bestvideo+bestaudio/best"
                                    .formatted(height, height)
                            : "best[height<=%d]/best".formatted(height));

                    if (ffmpeg) {
                        command.addAll(List.of("--merge-output-format", "mp4",
                                "--remux-video", "mp4"));
                    }

                }

                case AUDIO -> {
                    // unreachable: handled above
                }

            }

            // Prefer formats whose estimated size fits the cap - belt to max-filesize's
            // braces for sites that report sizes.
            command.add("-S");
            command.add("filesize_approx<" + request.maxFileBytes());

        }

        command.add("--max-filesize");
        command.add(String.valueOf(request.maxFileBytes()));
        command.add("--retries");
        command.add("2");
        command.add("--fragment-retries");
        command.add("2");
        command.add("--socket-timeout");
        command.add("30");

        if (ffmpeg && !audio) {
            command.add("--concurrent-fragments");
            command.add(String.valueOf(Math.max(1, options.concurrentFragments())));
        }

        if (options.writeSubs() && !audio) {
            command.add("--write-subs");
            command.add("--sub-langs");
            command.add("en.*,ru.*,uk.*");
        }

        command.add(request.url().toString());
        appendCommon(command, request);

        return command;

    }

    private void appendCommon(List<String> command, ExtractionRequest request) {

        if (options.proxy() != null && !options.proxy().isBlank()) {
            command.add("--proxy");
            command.add(options.proxy());
        }

        // Politeness applies to metadata requests too, not just the download run.
        if (!options.sleepRequests().isZero() && !options.sleepRequests().isNegative()) {
            command.add("--sleep-requests");
            command.add(String.format(Locale.ROOT, "%.2f",
                    options.sleepRequests().toMillis() / 1000.0));
        }

        // Cookies are the operator's account: attach them ONLY to requests allowed to
        // use them (owner-only by default - see downloader.cookies-owner-only).
        if (request.allowCookies() && options.cookiesFile() != null
                && !options.cookiesFile().isBlank()) {
            command.add("--cookies");
            command.add(options.cookiesFile());
        }

    }

    /** Live watchdog: sums the job dir (incl. .part fragments), throttled to 1/second
        per worker thread (the abort supplier is polled from that thread). */
    static boolean sizeExceeded(Path workDir, long cap) {

        long now = System.currentTimeMillis();
        Long last = WATCHDOG_LAST_CHECK.get();

        if (last != null && now - last < 1_000) {
            return false;
        }

        WATCHDOG_LAST_CHECK.set(now);

        try (var stream = Files.walk(workDir)) {

            long total = stream.filter(Files::isRegularFile)
                    .mapToLong(file -> {
                        try {
                            return Files.size(file);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();

            return total > cap;

        } catch (IOException e) {
            return false;
        }

    }

    private static final ThreadLocal<Long> WATCHDOG_LAST_CHECK = new ThreadLocal<>();

    /** Test hook for the throttle: force the next sizeExceeded call to actually measure. */
    static void resetWatchdogThrottle() {
        WATCHDOG_LAST_CHECK.remove();
    }

    private java.util.function.Consumer<String> progressLines(ProgressListener listener) {

        return line -> {

            if (line == null || listener.cancelled()) {
                return;
            }

            Matcher percent = PERCENT.matcher(line);

            if (percent.find()) {
                listener.onProgress((int) Math.min(100.0, Double.parseDouble(percent.group(1))),
                        null, 0, 0);
                return;
            }

            Matcher destination = DESTINATION.matcher(line);

            if (destination.find()) {
                listener.onProgress(null, "downloading", 0, 0);
                return;
            }

            if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]")
                    || line.startsWith("[VideoRemuxer]") || line.startsWith("[Fixup]")
                    || line.startsWith("[SubtitleConvertor]")) {
                listener.onProgress(null, "processing", 0, 0);
            }

        };

    }

    // ---- result ---------------------------------------------------------------------

    private List<ExtractedItem> scanWorkDir(Path workDir, List<Meta> metas,
                                            ExtractionRequest request) throws ExtractionException {

        Map<String, Meta> byId = new HashMap<>();
        metas.forEach(meta -> byId.putIfAbsent(meta.id(), meta));

        List<Path> files;

        try (var stream = Files.walk(workDir)) {

            files = stream.filter(Files::isRegularFile)
                    .filter(YtDlpExtractor::isFinal)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();

        } catch (IOException e) {
            throw new ExtractionException(Category.UNKNOWN, BACKEND_ID,
                    "Cannot read the work directory: " + e.getMessage(), e);
        }

        List<ExtractedItem> items = new ArrayList<>(files.size());

        for (Path file : files) {

            if (items.size() >= request.maxItems() * 2) {
                // subtitles/thumbnails ride along with their media; the cap counts media
                break;
            }

            String fileName = file.getFileName().toString();
            MediaKind kind = MediaKind.fromExtension(fileName);
            long size;

            try {
                size = Files.size(file);
            } catch (IOException e) {
                size = 0;
            }

            if (size == 0) {
                continue;
            }

            Meta meta = byId.get(stripExtension(fileName));

            items.add(new ExtractedItem(kind, file, size,
                    meta != null ? meta.title() : null,
                    meta != null ? meta.width() : null,
                    meta != null ? meta.height() : null,
                    meta != null ? meta.duration() : null,
                    meta != null ? meta.webpageUrl() : null));

        }

        return items;

    }

    /** Skips the intermediates yt-dlp leaves behind on a failed/aborted run. */
    private static boolean isFinal(Path file) {

        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        return !(name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".tmp")
                || name.endsWith("-f1.tmp") || name.startsWith("."));

    }

    private static String stripExtension(String fileName) {

        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;

    }

    // ---- error classification ---------------------------------------------------------

    /**
     * Ordered pattern table over the tool output; UNKNOWN classifications are counted
     * so wording drift in a new yt-dlp release becomes a visible metric, not a silent
     * degradation of every error message.
     */
    ExtractionException classify(ProcessRunner.Result result, String url) {

        String text = String.join("\n", result.stderrTail()) + "\n"
                + String.join("\n", result.stdoutTail());
        String errorLine = result.errorLine() != null ? result.errorLine() : text;

        for (Map.Entry<Pattern, Category> entry : CLASSIFIERS) {

            if (entry.getKey().matcher(text).find()) {

                if (entry.getValue() == Category.LOGIN_REQUIRED
                        && text.toLowerCase(Locale.ROOT).contains("private video")) {
                    // "Private video. Sign in if you've been granted access" is PRIVATE,
                    // not a login problem - the more specific phrase wins.
                    return new ExtractionException(Category.PRIVATE, BACKEND_ID,
                            firstError(errorLine, url));
                }

                return new ExtractionException(entry.getValue(), BACKEND_ID,
                        firstError(errorLine, url));

            }

        }

        if (metrics != null) {
            metrics.increment("extract_classify_unknown_total", "backend", BACKEND_ID);
        }

        log.debug("Unclassified yt-dlp failure for {}: {}", url, firstError(errorLine, url));

        return new ExtractionException(Category.UNKNOWN, BACKEND_ID, firstError(errorLine, url));

    }

    private static String firstError(String errorLine, String url) {

        String line = errorLine.trim();

        if (line.length() > 300) {
            line = line.substring(0, 300) + "...";
        }

        return line.isEmpty() ? "yt-dlp failed for " + url : line;

    }

}
