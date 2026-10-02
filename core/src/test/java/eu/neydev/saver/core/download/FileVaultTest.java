package eu.neydev.saver.core.download;

import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileVaultTest {

    private final MetricsRegistry metrics = new MetricsRegistry();

    private FileVault vault(Path dir, long maxBytes, Duration ttl, Duration grace) {
        return new FileVault(dir, maxBytes, ttl, grace, metrics);
    }

    @Test
    void createsJobDirsAndAccountsBytes(@TempDir Path dir) throws IOException {

        FileVault vault = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        vault.start();

        Path jobDir = vault.createJobDir("job-1");

        Files.write(jobDir.resolve("video.mp4"), new byte[1234]);
        vault.accountFor(1234);

        assertThat(vault.bytesInUse()).isEqualTo(1234);
        assertThat(vault.hasCapacity(10_000)).isTrue();

        vault.purge("job-1");

        assertThat(Files.exists(jobDir)).isFalse();
        assertThat(vault.bytesInUse()).isZero();

        vault.close();

    }

    @Test
    void quotaBlocksWhenFull(@TempDir Path dir) throws IOException {

        FileVault vault = vault(dir, 2_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        vault.start();

        Path jobDir = vault.createJobDir("job-big");
        Files.write(jobDir.resolve("a.bin"), new byte[1_500]);
        vault.accountFor(1_500);

        assertThat(vault.hasCapacity(1_000)).isFalse();
        assertThat(vault.hasCapacity(400)).isTrue();

        vault.close();

    }

    @Test
    void sweepRemovesReleasedJobsAfterTheGracePeriod(@TempDir Path dir) throws IOException {

        FileVault vault = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ZERO);
        vault.start();

        Path jobDir = vault.createJobDir("job-sent");
        Files.write(jobDir.resolve("photo.jpg"), new byte[100]);
        vault.accountFor(100);

        vault.release("job-sent");
        vault.sweep();

        assertThat(Files.exists(jobDir)).isFalse();
        assertThat(vault.bytesInUse()).isZero();

        vault.close();

    }

    @Test
    void sweepKeepsReleasedJobsInsideTheGracePeriod(@TempDir Path dir) throws IOException {

        FileVault vault = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        vault.start();

        Path jobDir = vault.createJobDir("job-retrying");
        Files.write(jobDir.resolve("video.mp4"), new byte[100]);

        vault.release("job-retrying");
        vault.sweep();

        // The dispatcher may still retry the upload: the file must survive the grace.
        assertThat(Files.exists(jobDir.resolve("video.mp4"))).isTrue();

        vault.close();

    }

    @Test
    void sweepCollectsOrphansOlderThanTheTtl(@TempDir Path dir) throws IOException {

        FileVault vault = vault(dir, 1_000_000, Duration.ofMinutes(1), Duration.ofMinutes(10));
        vault.start();

        Path orphan = vault.createJobDir("job-orphan");
        Path file = orphan.resolve("stuck.part");
        Files.write(file, new byte[50]);
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.from(Instant.now().minus(10, ChronoUnit.MINUTES)));

        vault.sweep();

        assertThat(Files.exists(orphan)).isFalse();

        vault.close();

    }

    @Test
    void startupAdoptsLeftoversFromAPreviousRun(@TempDir Path dir) throws IOException {

        Path leftover = dir.resolve("old-job");
        Files.createDirectories(leftover);
        Files.write(leftover.resolve("x.mp4"), new byte[777]);

        FileVault vault = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        vault.start();

        assertThat(vault.bytesInUse()).isEqualTo(777);

        vault.close();

    }

    @Test
    void reservationsCountAgainstTheQuotaBeforeAnyByteLands(@TempDir Path dir) {

        FileVault vault = vault(dir, 1_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        vault.start();

        assertThat(vault.reserve(600)).isTrue();
        // 600 reserved: another 600 does not fit even though nothing is on disk yet.
        assertThat(vault.hasCapacity(600)).isFalse();
        assertThat(vault.reserve(600)).isFalse();

        vault.unreserve(600);

        assertThat(vault.hasCapacity(600)).isTrue();

        vault.close();

    }

    @Test
    void aSecondInstanceOnTheSameVaultRefusesToStart(@TempDir Path dir) throws IOException {

        FileVault first = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        first.start();

        FileVault second = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));

        assertThatThrownBy(second::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vault lock");

        first.close();

        // After a clean close the lock is released and a new instance may start.
        FileVault third = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        third.start();
        third.close();

    }

    @Test
    void rejectsUnsafeJobIds(@TempDir Path dir) {

        FileVault vault = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));

        assertThatThrownBy(() -> vault.createJobDir("../../escape"))
                .isInstanceOf(IllegalArgumentException.class);

    }

    /**
     * The operator-facing regression: an external cleanup deleted the vault dir
     * mid-run, and the sweeper answered with "Vault sweep failed: data/media" once a
     * minute forever while the TTL cleanup silently never ran. The sweep must heal
     * the dir instead of reporting a bare path.
     */
    @Test
    void sweepRecreatesAVanishedBaseDir(@TempDir Path dir) throws IOException {

        FileVault vault = vault(dir, 1_000_000, Duration.ofMinutes(30), Duration.ofMinutes(10));
        vault.start();

        Files.deleteIfExists(dir.resolve(".lock"));
        Files.deleteIfExists(dir);

        assertThat(Files.exists(dir)).isFalse();

        vault.sweep();

        assertThat(Files.isDirectory(dir)).isTrue();

        vault.close();

    }

}
