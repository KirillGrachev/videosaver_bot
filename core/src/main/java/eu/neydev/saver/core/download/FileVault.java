package eu.neydev.saver.core.download;

import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The disk vault: where downloaded files live between the extractor and the platform
 * send. Three guarantees, because a downloader bot without them becomes a disk-filling
 * outage generator:
 *
 * <ul>
 *   <li>QUOTA: {@link #hasCapacity(long)} before a download starts (checked against the
 *       live counter AND the real free space of the filesystem - a counter cannot know
 *       what else shares the volume);</li>
 *   <li>GRACE: after a send, files live {@code sendGrace} longer (the dispatcher may
 *       still retry a rate-limited upload), then the sweeper removes them;</li>
 *   <li>TTL SAFETY NET: anything older than {@code ttl} is deleted no matter what -
 *       crashed jobs, orphaned dirs from a killed process, forgotten releases.</li>
 * </ul>
 */
public final class FileVault implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileVault.class);

    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final Path baseDir;
    private final long maxBytes;
    private final Duration ttl;
    private final Duration sendGrace;
    private final MetricsRegistry metrics;

    private final AtomicLong bytesInUse = new AtomicLong();
    /** Bytes reserved by in-flight jobs: the quota must see downloads BEFORE they land. */
    private final AtomicLong bytesReserved = new AtomicLong();
    private final Map<String, Instant> releasedAt = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private ScheduledExecutorService sweeper;
    private RandomAccessFile lockFile;
    private FileLock lock;

    public FileVault(Path baseDir, long maxBytes, Duration ttl, Duration sendGrace,
                     MetricsRegistry metrics) {
        this.baseDir = baseDir;
        this.maxBytes = maxBytes;
        this.ttl = ttl;
        this.sendGrace = sendGrace;
        this.metrics = metrics;
    }

    /**
     * Creates the base dir, takes the single-instance lock and adopts whatever a
     * previous incarnation left behind. The lock is the honest clustering answer: two
     * bots on one vault directory would race the sweeper and double-serve files, so
     * the second instance refuses to start instead of corrupting quietly.
     */
    public void start() {

        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the media vault: " + baseDir, e);
        }

        acquireLock();

        bytesInUse.set(scanBytes());

        if (bytesInUse.get() > 0) {
            log.warn("Vault adopted {} of orphaned files from a previous run "
                    + "(the TTL sweeper will collect them)", human(bytesInUse.get()));
        }

        metrics.gauge("vault_bytes", bytesInUse::get);

        if (!running.compareAndSet(false, true)) {
            return;
        }

        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {

            Thread thread = new Thread(runnable, "vault-sweeper");
            thread.setDaemon(true);

            return thread;

        });

        sweeper.scheduleWithFixedDelay(this::sweep,
                SWEEP_INTERVAL.toMillis(), SWEEP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        log.info("File vault ready: dir={}, quota={}, ttl={}", baseDir, human(maxBytes), ttl);

    }

    private void acquireLock() {

        try {

            lockFile = new RandomAccessFile(baseDir.resolve(".lock").toFile(), "rw");
            FileChannel channel = lockFile.getChannel();

            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }

            if (lock == null) {
                throw new IllegalStateException(
                        "Another Saver Bot instance holds the vault lock at " + baseDir
                                + " - one vault belongs to exactly one instance");
            }

        } catch (IOException e) {
            throw new IllegalStateException("Cannot lock the media vault at " + baseDir, e);
        }

    }

    /**
     * Reserves quota for an in-flight download: the effective used size becomes
     * inUse + reserved, so N workers downloading N half-gigabyte files cannot
     * collectively blow the cap between the submit check and the first accountFor.
     *
     * @return false when the reservation itself does not fit.
     */
    public boolean reserve(long bytes) {

        while (true) {

            long reservedNow = bytesReserved.get();

            if (bytesInUse.get() + reservedNow + bytes > maxBytes) {
                return false;
            }

            if (bytesReserved.compareAndSet(reservedNow, reservedNow + bytes)) {
                return true;
            }

        }

    }

    public void unreserve(long bytes) {
        bytesReserved.addAndGet(-Math.max(0, bytes));
    }

    public long bytesReserved() {
        return bytesReserved.get();
    }

    public Path createJobDir(String jobId) throws IOException {

        if (!jobId.matches("[A-Za-z0-9_-]+")) {
            // The id comes from our own generator, but the vault is the last line of
            // defense against a path-escape if that ever changes.
            throw new IllegalArgumentException("Unsafe job id: " + jobId);
        }

        Path dir = baseDir.resolve(jobId);
        Files.createDirectories(dir);

        return dir;

    }

    public Path jobDir(String jobId) {
        return baseDir.resolve(jobId);
    }

    /** Marks a job's files as "sent": deleted once the grace period expires. */
    public void release(String jobId) {
        releasedAt.put(jobId, Instant.now());
    }

    /** Immediate recursive delete (failed jobs, cancellations). */
    public void purge(String jobId) {

        releasedAt.remove(jobId);
        Path dir = baseDir.resolve(jobId);

        long freed = deleteRecursively(dir);
        bytesInUse.addAndGet(-freed);

    }

    public boolean hasCapacity(long additionalBytes) {

        if (bytesInUse.get() + bytesReserved.get() + additionalBytes > maxBytes) {
            return false;
        }

        try {

            FileStore store = Files.getFileStore(baseDir);
            return store.getUsableSpace() > additionalBytes + 100_000_000L;

        } catch (IOException e) {
            // An unreadable FileStore should not block downloads; the quota still holds.
            return true;
        }

    }

    public long bytesInUse() {
        return bytesInUse.get();
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** How long sent files survive - the result cache must not outlive them. */
    public Duration sendGraceSafe() {
        return sendGrace;
    }

    /** Accounts a file that an extractor wrote into a job dir. */
    public void accountFor(long bytes) {
        bytesInUse.addAndGet(bytes);
    }

    /** Package-private: the test triggers a sweep deterministically instead of waiting. */
    void sweep() {

        try {

            Instant now = Instant.now();

            try (Stream<Path> dirs = Files.list(baseDir)) {

                for (Path dir : dirs.filter(Files::isDirectory).toList()) {

                    String jobId = dir.getFileName().toString();
                    Instant released = releasedAt.get(jobId);

                    if (released != null && released.plus(sendGrace).isBefore(now)) {
                        purge(jobId);
                        continue;
                    }

                    Instant newest = newestMtime(dir);

                    if (newest != null && newest.plus(ttl).isBefore(now)) {

                        log.debug("Vault TTL sweep: {} (last write {})", jobId, newest);
                        purge(jobId);

                    }

                }

            }

        } catch (IOException | RuntimeException e) {
            // The sweeper must never die: a dead sweeper is a slowly filling disk.
            log.warn("Vault sweep failed: {}", e.getMessage());
        }

    }

    private static Instant newestMtime(Path dir) throws IOException {

        try (Stream<Path> files = Files.walk(dir)) {

            return files.filter(Files::isRegularFile)
                    .map(FileVault::mtime)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);

        }

    }

    private static Instant mtime(Path file) {

        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return null;
        }

    }

    private long deleteRecursively(Path dir) {

        AtomicLong freed = new AtomicLong();

        if (!Files.exists(dir)) {
            return 0;
        }

        try (Stream<Path> tree = Files.walk(dir)) {

            tree.sorted(Comparator.reverseOrder()).forEach(path -> {

                try {

                    long size = Files.isRegularFile(path) ? Files.size(path) : 0;

                    Files.deleteIfExists(path);

                    // Count only what was ACTUALLY deleted: on Windows a locked file
                    // survives deleteIfExists and must stay on the books until the
                    // next sweep finally collects it.
                    if (!Files.exists(path)) {
                        freed.addAndGet(size);
                    }

                } catch (IOException e) {
                    log.warn("Cannot delete {}: {}", path, e.getMessage());
                }

            });

        } catch (IOException e) {
            log.warn("Cannot walk {}: {}", dir, e.getMessage());
        }

        return freed.get();

    }

    private long scanBytes() {

        AtomicLong total = new AtomicLong();

        try (Stream<Path> files = Files.walk(baseDir)) {

            files.filter(Files::isRegularFile).forEach(file -> {

                try {
                    total.addAndGet(Files.size(file));
                } catch (IOException ignored) {
                    // an unreadable file contributes 0; the sweep will remove it
                }

            });

        } catch (IOException e) {
            log.warn("Cannot scan the vault at {}: {}", baseDir, e.getMessage());
        }

        return total.get();

    }

    private static String human(long bytes) {
        return eu.neydev.saver.core.util.ByteFormat.human(bytes);
    }

    @Override
    public void close() {

        if (!running.compareAndSet(true, false)) {
            return;
        }

        sweeper.shutdownNow();

        try {

            if (lock != null && lock.isValid()) {
                lock.release();
            }

            if (lockFile != null) {
                lockFile.close();
            }

        } catch (IOException e) {
            log.debug("Vault lock release: {}", e.getMessage());
        }

    }

}
