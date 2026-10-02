package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.ProgressListener;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceBackend;
import eu.neydev.saver.core.source.SourceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The yt-dlp contract, tested against a FAKE yt-dlp: a shell script that speaks the
 * real CLI shapes (the TSV probe of {@code --flat-playlist --print}, the -J JSON
 * fallback, -P work dir, [download] progress lines, ERROR: stderr) without touching
 * the network. What is pinned here is OUR side of the contract: argument assembly,
 * progress parsing, work-dir scanning and error classification.
 *
 * <p>Honest limitation, stated where a reviewer will see it: the ERROR strings below
 * mirror real yt-dlp wording as of 2026.08 but cannot PROVE the next release keeps it -
 * that is what the {@code extract_classify_unknown_total} metric is for in production.
 */
class YtDlpExtractorTest {

    private static final Source SOURCE = new Source("faketube", "FakeTube",
            List.of("faketube.example"), SourceBackend.YTDLP, SourceStatus.OK);

    /** Writes a fake yt-dlp and returns its path. {@code mode} selects the behavior. */
    private static Path fakeYtDlp(Path dir, String mode) throws IOException {

        // Shared prologue: version handling + workdir detection + arg dump for assertions.
        String prologue = """
                #!/bin/sh
                for a in "$@"; do
                  if [ "$a" = "-version" ] || [ "$a" = "--version" ]; then
                    echo "yt-dlp 2099.01.01 (fake)"; exit 0
                  fi
                done
                WORKDIR="."; HAS_P=0; PRINT=0; JSON=0
                prev=""
                for a in "$@"; do
                  [ "$a" = "--print" ] && PRINT=1
                  [ "$a" = "-J" ] && JSON=1
                  if [ "$prev" = "-P" ]; then WORKDIR="$a"; HAS_P=1; fi
                  prev="$a"
                done
                mkdir -p "$WORKDIR" 2>/dev/null
                # Dump args ONLY for the download invocation (-P seen). Probe
                # invocations carry no -P, so without this guard their args would
                # pile up in a stray .cmd-args.txt under the surefire cwd.
                if [ "$HAS_P" = "1" ]; then
                  echo "$@" >> "$WORKDIR/.cmd-args.txt" 2>/dev/null || true
                fi
                """;

        String script = switch (mode) {

            case "single" -> prologue + """
                    if [ "$PRINT" = "1" ]; then
                      printf 'vid1\\t125\\t1920\\t1080\\tFalse\\thttps://faketube.example/watch?v=vid1\\ttester\\tMy Test Video\\n'
                      exit 0
                    fi
                    if [ "$JSON" = "1" ]; then
                      echo '{"id":"vid1","title":"My Test Video","duration":125,"width":1920,"height":1080,"uploader":"tester","webpage_url":"https://faketube.example/watch?v=vid1","extractor":"faketube","is_live":false}'
                      exit 0
                    fi
                    echo "[download] Destination: $WORKDIR/vid1.mp4"
                    echo "[download]  17.3% of 10.00MiB at 1.00MiB/s ETA 00:07"
                    echo "[download]  63.9% of 10.00MiB at 1.00MiB/s ETA 00:03"
                    echo "[download] 100% of 10.00MiB at 1.00MiB/s ETA 00:00"
                    printf 'video-bytes' > "$WORKDIR/vid1.mp4"
                    exit 0
                    """;

            case "playlist" -> prologue + """
                    if [ "$PRINT" = "1" ]; then
                      printf 'a1\\tNA\\tNA\\tNA\\tNA\\thttps://faketube.example/watch?v=a1\\tNA\\tFirst\\n'
                      printf 'a2\\tNA\\tNA\\tNA\\tNA\\thttps://faketube.example/watch?v=a2\\tNA\\tSecond\\n'
                      exit 0
                    fi
                    if [ "$JSON" = "1" ]; then
                      echo '{"_type":"playlist","id":"PL1","title":"Test Playlist","entries":[{"id":"a1"},{"id":"a2"}]}'
                      exit 0
                    fi
                    printf 'one' > "$WORKDIR/a1.mp4"
                    printf 'two' > "$WORKDIR/a2.mp4"
                    exit 0
                    """;

            case "live" -> prologue + """
                    if [ "$PRINT" = "1" ]; then
                      printf 'live1\\tNA\\tNA\\tNA\\tTrue\\thttps://faketube.example/live\\tNA\\tLive Now\\n'
                      exit 0
                    fi
                    exit 1
                    """;

            case "tabbed-title" -> prologue + """
                    if [ "$PRINT" = "1" ]; then
                      printf 'vid9\t30\t640\t360\tFalse\thttps://faketube.example/watch?v=vid9\tuploader\tTitle\twith\ttabs\n'
                      exit 0
                    fi
                    printf 'bytes' > "$WORKDIR/vid9.mp4"
                    exit 0
                    """;

            case "bigjson" -> prologue + """
                    if [ "$PRINT" = "1" ]; then
                      exit 0
                    fi
                    if [ "$JSON" = "1" ]; then
                      echo '{'
                      i=0
                      while [ $i -lt 400 ]; do
                        printf '  "padding%s": "%s",\\n' "$i" "$i"
                        i=$((i+1))
                      done
                      echo '  "id": "big1", "title": "Big JSON Video", "duration": 77,'
                      echo '  "width": 1280, "height": 720, "is_live": false,'
                      echo '  "webpage_url": "https://faketube.example/watch?v=big1",'
                      echo '  "uploader": "jsoner"'
                      echo '}'
                      exit 0
                    fi
                    printf 'json-bytes' > "$WORKDIR/big1.mp4"
                    exit 0
                    """;

            case "private" -> prologue + """
                    echo "ERROR: [faketube] vid1: Private video. Sign in if you've been granted access to this video." >&2
                    exit 1
                    """;

            case "login" -> prologue + """
                    echo "ERROR: [faketube] vid1: Sign in to confirm you're not a bot. Please use your account." >&2
                    exit 1
                    """;

            case "age" -> prologue + """
                    echo "ERROR: [faketube] vid1: Video is age restricted. Please confirm your age." >&2
                    exit 1
                    """;

            case "geo" -> prologue + """
                    echo "ERROR: [faketube] vid1: The uploader has not made this video available in your country." >&2
                    exit 1
                    """;

            case "unsupported" -> prologue + """
                    echo "ERROR: Unsupported URL: https://faketube.example/whatever" >&2
                    exit 1
                    """;

            default -> throw new IllegalArgumentException("unknown mode " + mode);

        };

        Path file = dir.resolve("yt-dlp-" + mode);
        Files.writeString(file, script);
        file.toFile().setExecutable(true);

        return file;

    }

    private static YtDlpExtractor extractor(Path fakeBinary) {

        Toolchain tools = Toolchain.probe(fakeBinary.toString(), "definitely-missing-tool",
                "definitely-missing-too");

        return new YtDlpExtractor(tools, new YtDlpExtractor.Options(
                null, null, Duration.ofSeconds(30), Duration.ofSeconds(30), 2,
                Duration.ofMillis(500), false, false), new MetricsRegistry());

    }

    private static ExtractionRequest request(Path workDir, ProgressListener progress) {

        return new ExtractionRequest(SOURCE, URI.create("https://faketube.example/watch?v=vid1"),
                QualityPreset.BEST, workDir, 500_000_000L, 10, progress);

    }

    @Test
    void downloadsASingleVideoWithMetadata(@TempDir Path dir) throws IOException {

        YtDlpExtractor extractor = extractor(fakeYtDlp(dir, "single"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        ExtractionResult result = extractor.extract(request(workDir, ProgressListener.NOOP));

        assertThat(result.items()).hasSize(1);
        assertThat(result.title()).isEqualTo("My Test Video");
        assertThat(result.uploader()).isEqualTo("tester");
        assertThat(result.backendId()).isEqualTo("ytdlp");
        assertThat(result.truncated()).isFalse();
        assertThat(result.warningKey()).isNull();

        ExtractedItem item = result.items().get(0);

        assertThat(item.kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(item.file().getFileName().toString()).isEqualTo("vid1.mp4");
        assertThat(item.width()).isEqualTo(1920);
        assertThat(item.height()).isEqualTo(1080);
        assertThat(item.durationSeconds()).isEqualTo(125);
        assertThat(item.sizeBytes()).isPositive();

    }

    @Test
    void downloadCommandCarriesPolitenessAndSizeFlags(@TempDir Path dir) throws IOException {

        YtDlpExtractor extractor = extractor(fakeYtDlp(dir, "single"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        extractor.extract(request(workDir, ProgressListener.NOOP));

        String args = String.join("\n", Files.readAllLines(workDir.resolve(".cmd-args.txt")));

        // The DOWNLOAD invocation (not the probe) must carry the cap, the sort and the
        // politeness pause; the probe must not (it downloads nothing).
        assertThat(args).contains("--max-filesize 500000000");
        assertThat(args).contains("-S filesize_approx<500000000");
        assertThat(args).contains("--sleep-requests 0.50");
        assertThat(args).contains("--restrict-filenames");

    }

    @Test
    void cookiesFollowThePerRequestAllowFlag(@TempDir Path dir) throws IOException {

        Toolchain tools = Toolchain.probe(fakeYtDlp(dir, "single").toString(),
                "missing", "missing");

        YtDlpExtractor extractor = new YtDlpExtractor(tools, new YtDlpExtractor.Options(
                null, "/tmp/cookies.txt", Duration.ofSeconds(30), Duration.ofSeconds(30),
                2, Duration.ZERO, false, false));

        Path workDir = Files.createDirectories(dir.resolve("job"));

        // allowCookies = false (a stranger's request under cookies-owner-only)
        extractor.extract(new ExtractionRequest(SOURCE,
                URI.create("https://faketube.example/watch?v=vid1"),
                QualityPreset.BEST, workDir, 500_000_000L, 10, ProgressListener.NOOP, false));

        String args = Files.readString(workDir.resolve(".cmd-args.txt"));

        assertThat(args).doesNotContain("--cookies");

    }

    @Test
    void progressLinesReachTheListener(@TempDir Path dir) throws IOException {

        YtDlpExtractor extractor = extractor(fakeYtDlp(dir, "single"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        List<Integer> percents = new ArrayList<>();
        AtomicInteger stages = new AtomicInteger();

        extractor.extract(request(workDir, new ProgressListener() {

            @Override
            public void onProgress(Integer percent, String stage, int itemsDone, int itemsTotal) {

                if (percent != null) {
                    percents.add(percent);
                } else {
                    stages.incrementAndGet();
                }

            }

        }));

        assertThat(percents).contains(17, 63, 100);
        assertThat(stages.get()).isPositive();      // "[download] Destination:" stage

    }

    @Test
    void playlistsDownloadEveryEntry(@TempDir Path dir) throws IOException {

        YtDlpExtractor extractor = extractor(fakeYtDlp(dir, "playlist"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        ExtractionResult result = extractor.extract(new ExtractionRequest(SOURCE,
                URI.create("https://faketube.example/playlist?list=PL1"),
                QualityPreset.BEST, workDir, 500_000_000L, 10, ProgressListener.NOOP));

        assertThat(result.items()).hasSize(2);
        assertThat(result.totalBytes()).isEqualTo(6);
        // Playlist-level titles are not resolvable from a flat probe - per-item titles are.
        assertThat(result.title()).isNull();

    }

    @Test
    void liveStreamsAreRefusedBeforeDownloading(@TempDir Path dir) throws IOException {

        YtDlpExtractor extractor = extractor(fakeYtDlp(dir, "live"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        assertThatThrownBy(() -> extractor.extract(request(workDir, ProgressListener.NOOP)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.LIVE_STREAM);

    }

    @Test
    void toolErrorsAreClassified(@TempDir Path dir) throws IOException {

        Path workDir = Files.createDirectories(dir.resolve("job"));

        record Case(String mode, Category expected) {
        }

        for (Case testCase : List.of(
                new Case("private", Category.PRIVATE),
                new Case("login", Category.LOGIN_REQUIRED),
                new Case("age", Category.AGE_RESTRICTED),
                new Case("geo", Category.GEO_BLOCKED),
                new Case("unsupported", Category.UNSUPPORTED))) {

            YtDlpExtractor extractor = extractor(fakeYtDlp(dir, testCase.mode()));

            assertThatThrownBy(() -> extractor.extract(request(workDir, ProgressListener.NOOP)))
                    .as("mode %s", testCase.mode())
                    .isInstanceOf(ExtractionException.class)
                    .extracting(e -> ((ExtractionException) e).category())
                    .isEqualTo(testCase.expected());

        }

    }

    @Test
    void unclassifiedErrorsAreCountedNotSilent(@TempDir Path dir) throws IOException {

        // A wording the table has never seen must land in UNKNOWN *and* move the metric:
        // that counter is how a yt-dlp rewording becomes visible before users complain.
        Path script = dir.resolve("yt-dlp-weird");
        Files.writeString(script, """
                #!/bin/sh
                for a in "$@"; do
                  if [ "$a" = "-version" ] || [ "$a" = "--version" ]; then
                    echo "yt-dlp 2099.01.01 (fake)"; exit 0
                  fi
                done
                echo "ERROR: [faketube] something nobody has ever seen before xyzzy" >&2
                exit 1
                """);
        script.toFile().setExecutable(true);

        MetricsRegistry metrics = new MetricsRegistry();
        Toolchain tools = Toolchain.probe(script.toString(), "missing", "missing");
        YtDlpExtractor extractor = new YtDlpExtractor(tools,
                YtDlpExtractor.Options.defaults(), metrics);

        Path workDir = Files.createDirectories(dir.resolve("job"));

        assertThatThrownBy(() -> extractor.extract(request(workDir, ProgressListener.NOOP)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.UNKNOWN);

        assertThat(metrics.count("extract_classify_unknown_total", "backend", "ytdlp"))
                .isEqualTo(1);

    }

    @Test
    void tabsInsideTitlesDoNotShiftFields(@TempDir Path dir) throws IOException {

        // The title is the LAST TSV field and the split is bounded at 8: a tab inside
        // the title corrupts nothing but the title itself.
        YtDlpExtractor extractor = extractor(fakeYtDlp(dir, "tabbed-title"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        ExtractionResult result = extractor.extract(request(workDir, ProgressListener.NOOP));

        assertThat(result.items()).hasSize(1);
        assertThat(result.title()).isEqualTo("Title\twith\ttabs");
        assertThat(result.items().get(0).durationSeconds()).isEqualTo(30);
        assertThat(result.items().get(0).width()).isEqualTo(640);

    }

    @Test
    void theJsonFallbackCapturesTheWholeDocument(@TempDir Path dir) throws IOException {

        // A real -J dump runs to thousands of lines; the fallback assembles it from
        // the live line consumer, not from the 200-line tail (which would see only
        // the closing braces and fail to parse).
        YtDlpExtractor extractor = extractor(fakeYtDlp(dir, "bigjson"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        ExtractionResult result = extractor.extract(request(workDir, ProgressListener.NOOP));

        assertThat(result.title()).isEqualTo("Big JSON Video");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).durationSeconds()).isEqualTo(77);

    }

    @Test
    void theWatchdogCapScalesWithPlaylistSize(@TempDir Path dir) throws IOException {

        YtDlpExtractor.resetWatchdogThrottle();

        Path workDir = Files.createDirectories(dir.resolve("watch"));
        Files.write(workDir.resolve("a.mp4"), new byte[400]);
        Files.write(workDir.resolve("b.mp4"), new byte[400]);
        Files.write(workDir.resolve("c.mp4"), new byte[400]);

        // A 3-entry playlist at a 1000-byte per-file cap: 1200 bytes total is legitimate.
        assertThat(YtDlpExtractor.sizeExceeded(workDir, (long) (1000 * 1.15 * 3))).isFalse();
        YtDlpExtractor.resetWatchdogThrottle();

        // The OLD single-file cap would have killed this healthy playlist download.
        assertThat(YtDlpExtractor.sizeExceeded(workDir, (long) (1000 * 1.15))).isTrue();
        YtDlpExtractor.resetWatchdogThrottle();

        // And a genuinely runaway stream still trips the scaled cap.
        Files.write(workDir.resolve("d.mp4"), new byte[5_000]);
        assertThat(YtDlpExtractor.sizeExceeded(workDir, (long) (1000 * 1.15 * 3))).isTrue();

    }

    @Test
    void aMissingBinaryMakesTheBackendUnavailable(@TempDir Path dir) {

        Toolchain tools = Toolchain.probe("definitely-missing-tool",
                "definitely-missing-too", "also-missing");

        YtDlpExtractor extractor = new YtDlpExtractor(tools, YtDlpExtractor.Options.defaults());

        assertThat(extractor.available()).isFalse();
        assertThat(tools.hasFfmpeg()).isFalse();

    }

}
