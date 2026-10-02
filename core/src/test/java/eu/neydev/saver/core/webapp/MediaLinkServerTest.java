package eu.neydev.saver.core.webapp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MediaLinkServerTest {

    @Test
    void disabledWithoutAPublicUrl() {

        MediaLinkServer server = new MediaLinkServer(null, Duration.ofMinutes(30));

        assertThat(server.publishing()).isFalse();
        assertThat(server.publish(Path.of("x"), "x.mp4", "video/mp4")).isEmpty();

    }

    @Test
    void publishesTokensAndResolvesThem(@TempDir Path dir) throws IOException {

        Path file = dir.resolve("video.mp4");
        Files.writeString(file, "bytes");

        MediaLinkServer server = new MediaLinkServer(
                "https://saver.example.com/", Duration.ofMinutes(30));

        String url = server.publish(file, "video.mp4", "video/mp4").orElseThrow();

        assertThat(url).startsWith("https://saver.example.com/media/");
        assertThat(server.publishing()).isTrue();

        String token = url.substring(url.lastIndexOf('/') + 1);

        assertThat(server.resolve(token)).isPresent();
        assertThat(server.resolve(token).orElseThrow().mimeType()).isEqualTo("video/mp4");

        // Tokens are unguessable: 128 bits, url-safe, no padding.
        assertThat(token).hasSizeGreaterThanOrEqualTo(20).doesNotContain("=");

    }

    @Test
    void unknownAndExpiredTokensResolveToNothing(@TempDir Path dir) throws Exception {

        Path file = dir.resolve("photo.jpg");
        Files.writeString(file, "bytes");

        MediaLinkServer server = new MediaLinkServer("https://x.example", Duration.ofMillis(1));

        String url = server.publish(file, "photo.jpg", "image/jpeg").orElseThrow();
        String token = url.substring(url.lastIndexOf('/') + 1);

        Thread.sleep(5);

        assertThat(server.resolve(token)).isEmpty();
        assertThat(server.resolve("no-such-token")).isEmpty();

    }

}
