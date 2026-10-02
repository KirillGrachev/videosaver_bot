package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ToolUpdaterTest {

    /** A fake tool that "updates" itself: -U flips the version it reports. */
    private static Path fakeUpdatableTool(Path dir, String name) throws IOException {

        Path state = dir.resolve(name + ".updated");
        Path file = dir.resolve(name);

        Files.writeString(file, """
                #!/bin/sh
                for a in "$@"; do
                  case "$a" in
                    -version|--version)
                      if [ -f "%STATE%" ]; then echo "%NAME% 2.0.0"; else echo "%NAME% 1.0.0"; fi
                      exit 0 ;;
                    -U|--update)
                      touch "%STATE%"
                      echo "Updated %NAME%"
                      exit 0 ;;
                  esac
                done
                exit 0
                """.replace("%STATE%", state.toString()).replace("%NAME%", name));
        file.toFile().setExecutable(true);

        return file;

    }

    @Test
    void updateRunRefreshesTheFingerprint(@TempDir Path dir) throws IOException {

        Path ytDlp = fakeUpdatableTool(dir, "yt-dlp-fake");
        Path galleryDl = fakeUpdatableTool(dir, "gallery-dl-fake");

        Toolchain toolchain = Toolchain.probe(ytDlp.toString(), galleryDl.toString(),
                "missing-ffmpeg", "missing-ffprobe");
        MetricsRegistry metrics = new MetricsRegistry();

        assertThat(toolchain.ytDlp().version()).contains("1.0.0");

        ToolUpdater updater = new ToolUpdater(toolchain, Duration.ofHours(24), metrics);
        updater.updateAll();

        assertThat(toolchain.ytDlp().version()).contains("2.0.0");
        assertThat(toolchain.galleryDl().version()).contains("2.0.0");
        assertThat(metrics.count("tools_updates_total", "tool", "yt-dlp", "result", "ok"))
                .isEqualTo(1);

    }

    @Test
    void missingToolsAreSkippedNotFailed(@TempDir Path dir) {

        Toolchain toolchain = Toolchain.probe("missing-a", "missing-b",
                "missing-ffmpeg", "missing-ffprobe");
        MetricsRegistry metrics = new MetricsRegistry();

        ToolUpdater updater = new ToolUpdater(toolchain, Duration.ofHours(1), metrics);
        updater.updateAll();

        assertThat(metrics.count("tools_updates_total", "tool", "yt-dlp",
                "result", "skipped_missing")).isEqualTo(1);

    }

    @Test
    void zeroIntervalMeansDisabled() throws IOException {

        Toolchain toolchain = Toolchain.probe("missing-a", "missing-b",
                "missing-ffmpeg", "missing-ffprobe");

        assertThat(new ToolUpdater(toolchain, Duration.ZERO, new MetricsRegistry())
                .enabled()).isFalse();

    }

}
