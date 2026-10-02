package eu.neydev.saver.core.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MediaProbeTest {

    private static Path fakeFfprobe(Path dir, String output) throws IOException {

        Path file = dir.resolve("ffprobe");
        Files.writeString(file, "#!/bin/sh\nprintf '" + output.replace("'", "") + "'\n");
        file.toFile().setExecutable(true);

        return file;

    }

    @Test
    void parsesVideoStreamAndFormatLines(@TempDir Path dir) throws IOException {

        MediaProbe probe = new MediaProbe(
                fakeFfprobe(dir, "1920,1080\\n12.480000\\n").toString());

        Path media = Files.write(dir.resolve("clip.mp4"), new byte[]{1});

        MediaProbe.Probed probed = probe.probe(media);

        assertThat(probed.width()).isEqualTo(1920);
        assertThat(probed.height()).isEqualTo(1080);
        assertThat(probed.durationSeconds()).isEqualTo(12);

    }

    @Test
    void audioOnlyFilesYieldJustTheDuration(@TempDir Path dir) throws IOException {

        MediaProbe probe = new MediaProbe(fakeFfprobe(dir, "215.03\\n").toString());

        MediaProbe.Probed probed = probe.probe(Files.write(dir.resolve("track.mp3"), new byte[]{1}));

        assertThat(probed.durationSeconds()).isEqualTo(215);
        assertThat(probed.width()).isNull();

    }

    @Test
    void garbageOutputIsEmptyNotFatal(@TempDir Path dir) throws IOException {

        MediaProbe probe = new MediaProbe(fakeFfprobe(dir, "not-a-probe-line\\n").toString());

        MediaProbe.Probed probed = probe.probe(Files.write(dir.resolve("x.bin"), new byte[]{1}));

        assertThat(probed.isEmpty()).isTrue();

    }

    @Test
    void withoutFfprobeTheProbeIsInert() {

        MediaProbe probe = new MediaProbe(null);

        assertThat(probe.available()).isFalse();
        assertThat(probe.probe(Path.of("whatever"))).isEqualTo(MediaProbe.Probed.EMPTY);

    }

}
