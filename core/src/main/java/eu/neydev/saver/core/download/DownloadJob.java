package eu.neydev.saver.core.download;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.QualityPreset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One in-flight download request. Mutable by nature (a worker thread drives it through
 * the phases while the router may cancel it from another thread), so state lives in
 * volatiles and the cancel flag is atomic - but every transition is one-way: a job never
 * leaves a terminal phase.
 *
 * <p>Jobs are deliberately EPHEMERAL: they are not resumed after a restart (the storage
 * closes leftovers as INTERRUPTED at startup). A user whose download died with the
 * process re-sends the link - honest and simple, versus a resume protocol for files
 * whose source URLs expire in minutes anyway.
 */
public final class DownloadJob {

    public enum Phase { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    private final String id;
    private final PlatformUser user;
    private final String chatId;
    private final String rawUrl;
    private final QualityPreset quality;
    private final Locale locale;
    private final Instant createdAt;

    private volatile Phase phase = Phase.QUEUED;
    private volatile ExtractionException.@Nullable Category errorCategory;
    private volatile @Nullable String errorDetail;
    private volatile @Nullable String statusMessageId;
    private volatile @Nullable Thread worker;
    private volatile long bytesDownloaded;
    private volatile int itemCount;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;

    /**
     * A cache hit discovered at submit time: the worker serves these files instead of
     * downloading. Carried on the job so cache hits ride the same queue (and the same
     * worker limits) as fresh downloads.
     */
    public record CachePayload(String jobId, Object entry) {
    }

    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile CachePayload cachedPayload;

    public DownloadJob(String id, PlatformUser user, String chatId, String rawUrl,
                       QualityPreset quality, Locale locale, Instant createdAt) {
        this.id = id;
        this.user = user;
        this.chatId = chatId;
        this.rawUrl = rawUrl;
        this.quality = quality;
        this.locale = locale;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public PlatformUser user() {
        return user;
    }

    public Platform platform() {
        return user.platform();
    }

    public String chatId() {
        return chatId;
    }

    public String rawUrl() {
        return rawUrl;
    }

    public QualityPreset quality() {
        return quality;
    }

    public Locale locale() {
        return locale;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isTerminal() {
        Phase current = phase;
        return current == Phase.SUCCEEDED || current == Phase.FAILED || current == Phase.CANCELLED;
    }

    public void markRunning(@NotNull Thread worker, Instant now) {
        this.worker = worker;
        this.startedAt = now;
        this.phase = Phase.RUNNING;
    }

    public void markSucceeded(int itemCount, long bytes, Instant now) {
        this.itemCount = itemCount;
        this.bytesDownloaded = bytes;
        this.finishedAt = now;
        this.phase = Phase.SUCCEEDED;
    }

    public void markFailed(ExtractionException.Category category, @Nullable String detail, Instant now) {
        this.errorCategory = category;
        this.errorDetail = detail;
        this.finishedAt = now;
        this.phase = Phase.FAILED;
    }

    public void markCancelled(Instant now) {
        this.finishedAt = now;
        this.phase = Phase.CANCELLED;
    }

    public ExtractionException.@Nullable Category errorCategory() {
        return errorCategory;
    }

    public @Nullable String errorDetail() {
        return errorDetail;
    }

    public @Nullable String statusMessageId() {
        return statusMessageId;
    }

    public void setStatusMessageId(@Nullable String statusMessageId) {
        this.statusMessageId = statusMessageId;
    }

    public long bytesDownloaded() {
        return bytesDownloaded;
    }

    public void addBytes(long bytes) {
        this.bytesDownloaded += bytes;
    }

    public int itemCount() {
        return itemCount;
    }

    public @Nullable Instant startedAt() {
        return startedAt;
    }

    public @Nullable Instant finishedAt() {
        return finishedAt;
    }

    /** Milliseconds between start and finish; 0 when the job never ran. */
    public long durationMillis() {

        if (startedAt == null || finishedAt == null) {
            return 0;
        }

        return Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli());

    }

    /**
     * Requests cancellation: sets the flag (extractors poll it), interrupts the worker
     * (blocking waits and the process runner react) - whichever the worker touches
     * first. Idempotent; a terminal job ignores it.
     */
    public boolean cancel() {

        if (isTerminal()) {
            return false;
        }

        cancelRequested.set(true);

        Thread current = worker;

        if (current != null) {
            current.interrupt();
        }

        return true;

    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public void attachCached(String sourceJobId, Object entry) {
        this.cachedPayload = new CachePayload(sourceJobId, entry);
    }

    /** Returns the payload at most once: a re-run job downloads for real. */
    public @Nullable CachePayload takeCached() {

        CachePayload payload = cachedPayload;
        cachedPayload = null;

        return payload;

    }

}
