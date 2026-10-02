package eu.neydev.saver.core.download;

import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.command.JobReplies;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.ExtractorChain;
import eu.neydev.saver.core.extract.ProgressListener;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.extract.backend.Toolchain;
import eu.neydev.saver.core.media.MediaAttachment;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.media.MediaProbe;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.pipeline.OutboundDispatcher;
import eu.neydev.saver.core.pipeline.TokenBucket;
import eu.neydev.saver.core.security.UrlIntake;
import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceCatalog;
import eu.neydev.saver.core.source.SourceCatalogHolder;
import eu.neydev.saver.core.source.SourceMatcher;
import eu.neydev.saver.core.storage.JobRepository;
import eu.neydev.saver.core.storage.UsageRepository;
import eu.neydev.saver.core.util.ByteFormat;
import eu.neydev.saver.core.util.TraceId;
import eu.neydev.saver.core.webapp.MediaLinkServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The download engine: a bounded queue, a pool of virtual-thread workers, and the full
 * job lifecycle from "user pasted a link" to "file delivered, disk freed, usage counted".
 *
 * <p>Invariants worth knowing before changing anything:
 *
 * <ul>
 *   <li>ALL cheap refusals (URL, quotas, concurrency, disabled source, vault space)
 *       happen in {@link #submit} SYNCHRONOUSLY and return an outcome enum - a queued
 *       job is a promise;</li>
 *   <li>the hourly quota and the job-log INSERT are ONE statement - no check-then-insert
 *       race even under parallel submits;</li>
 *   <li>the vault quota is RESERVED at submit and released at completion: N workers
 *       downloading N big files cannot collectively overflow the disk between the
 *       submit check and the first byte landing;</li>
 *   <li>progress edits go only to platforms that can actually EDIT (WhatsApp/Viber
 *       would otherwise turn every 10% into a new message);</li>
 *   <li>every exit path frees the vault AND the reservation: success releases with
 *       grace, failure purges, the sweeper is the net under all three;</li>
 *   <li>repeat requests within the vault grace window are served FROM CACHE - the
 *       files are still on disk, re-downloading them would be pure waste.</li>
 * </ul>
 */
public final class JobManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobManager.class);

    /** Telegram's hard album size; other platforms never see groups (dispatcher expands). */
    private static final int MAX_GROUP_SIZE = 10;

    /** Why a submit() was refused - each value maps to one i18n key. */
    public enum Rejection {
        QUEUE_FULL("message.error.queue_full"),
        CONCURRENT_LIMIT("message.error.concurrent"),
        QUOTA_HOUR("message.error.quota_hour"),
        QUOTA_DAY("message.error.quota_day"),
        QUOTA_BYTES("message.error.quota_bytes"),
        SOURCE_DISABLED("message.error.source_disabled"),
        VAULT_FULL("message.error.vault_full");

        private final String messageKey;

        Rejection(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }

    }

    public sealed interface SubmitOutcome {

        record Accepted(String jobId) implements SubmitOutcome {
        }

        record InvalidUrl(String detail) implements SubmitOutcome {
        }

        record Refused(Rejection rejection, Map<String, Object> params) implements SubmitOutcome {

            public Refused(Rejection rejection) {
                this(rejection, Map.of());
            }

        }

    }

    private static final int CACHE_MAX_ENTRIES = 500;

    private final AppConfig config;
    private final AppConfig.Downloader downloader;
    private final AppConfig.Limits limits;
    private final SourceCatalogHolder catalogHolder;
    private final SourceMatcher matcher;
    private final UrlIntake intake;
    private final ExtractorChain chain;
    private final FileVault vault;
    private final SizePolicy sizePolicy;
    private final MediaProbe mediaProbe;
    private final Toolchain tools;
    private final JobRepository jobs;
    private final UsageRepository usage;
    private final OutboundDispatcher dispatcher;
    private final JobReplies replies;
    private final MetricsRegistry metrics;
    private final Clock clock;
    private final @Nullable MediaLinkServer mediaLinks;

    private final BlockingQueue<DownloadJob> queue;
    private final Map<PlatformUser, Set<DownloadJob>> activeByUser = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> sourceBuckets = new ConcurrentHashMap<>();
    private final ResultCache resultCache;
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean();

    private Thread[] workers = new Thread[0];
    private ScheduledExecutorService housekeeper;

    public JobManager(AppConfig config,
                      SourceCatalogHolder catalogHolder,
                      SourceMatcher matcher,
                      UrlIntake intake,
                      ExtractorChain chain,
                      FileVault vault,
                      SizePolicy sizePolicy,
                      MediaProbe mediaProbe,
                      Toolchain tools,
                      JobRepository jobs,
                      UsageRepository usage,
                      OutboundDispatcher dispatcher,
                      JobReplies replies,
                      MetricsRegistry metrics,
                      Clock clock,
                      @Nullable MediaLinkServer mediaLinks) {

        this.config = config;
        this.downloader = config.downloader();
        this.limits = config.limits();
        this.catalogHolder = catalogHolder;
        this.matcher = matcher;
        this.intake = intake;
        this.chain = chain;
        this.vault = vault;
        this.sizePolicy = sizePolicy;
        this.mediaProbe = mediaProbe;
        this.tools = tools;
        this.jobs = jobs;
        this.usage = usage;
        this.dispatcher = dispatcher;
        this.replies = replies;
        this.metrics = metrics;
        this.clock = clock;
        this.mediaLinks = mediaLinks;
        this.queue = new ArrayBlockingQueue<>(downloader.queueCapacity());
        this.resultCache = new ResultCache(CACHE_MAX_ENTRIES,
                vault.sendGraceSafe(), clock);

    }

    // ---- lifecycle ----------------------------------------------------------------

    public void start() {

        if (!running.compareAndSet(false, true)) {
            return;
        }

        workers = new Thread[downloader.workers()];

        for (int i = 0; i < downloader.workers(); i++) {

            workers[i] = Thread.ofVirtual()
                    .name("download-worker-" + i)
                    .start(this::workerLoop);

        }

        startHousekeeper();

        metrics.gauge("jobs_active", activeCount::get);
        metrics.gauge("jobs_queued", queue::size);

        log.info("Download pipeline started: workers={}, queue={}, per-source {} job/s",
                downloader.workers(), downloader.queueCapacity(),
                downloader.perSourcePerSecond());

    }

    /**
     * Daily housekeeping: log retention (privacy AND growth), cache expiry, and the
     * map hygiene that keeps a long-running bot from accumulating dead users.
     */
    private void startHousekeeper() {

        housekeeper = Executors.newSingleThreadScheduledExecutor(runnable -> {

            Thread thread = new Thread(runnable, "job-housekeeper");
            thread.setDaemon(true);

            return thread;

        });

        long periodHours = Math.max(1, Math.min(24,
                downloader.jobLogRetention().toHours() / 4));

        housekeeper.scheduleWithFixedDelay(this::housekeep,
                Duration.ofMinutes(10).toMillis(),
                TimeUnit.HOURS.toMillis(periodHours), TimeUnit.MILLISECONDS);

    }

    void housekeep() {

        try {

            if (!downloader.jobLogRetention().isZero() && !downloader.jobLogRetention().isNegative()) {

                Instant cutoff = clock.instant().minus(downloader.jobLogRetention());
                jobs.pruneBefore(cutoff);
                usage.pruneBeforeDay(LocalDate.ofInstant(cutoff, ZoneOffset.UTC).toEpochDay());

            }

            resultCache.purgeExpired();

            // Users whose jobs all finished leave an empty set behind; without this
            // the map grows one entry per unique user forever.
            activeByUser.entrySet().removeIf(entry -> {
                entry.getValue().removeIf(DownloadJob::isTerminal);
                return entry.getValue().isEmpty();
            });

        } catch (RuntimeException e) {
            log.warn("Housekeeping failed: {}", e.getMessage());
        }

    }

    @Override
    public void close() {

        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Graceful drain: running downloads get shutdown-drain to finish (a killed
        // download is a lost download for the user), then the rest is interrupted.
        Duration drain = downloader.shutdownDrain();
        long deadline = System.currentTimeMillis() + Math.max(0, drain.toMillis());

        while (activeCount.get() > 0 && System.currentTimeMillis() < deadline) {

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

        }

        for (Thread worker : workers) {
            worker.interrupt();
        }

        // Join: worker finally-blocks write to storage and release vault files, and the
        // caller closes both right after us - a still-running worker would race them.
        for (Thread worker : workers) {

            try {
                worker.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

        }

        if (housekeeper != null) {
            housekeeper.shutdownNow();
        }

        log.info("Download pipeline stopped ({} job(s) left in queue)", queue.size());

    }

    public int activeCount() {
        return activeCount.get();
    }

    public int queuedCount() {
        return queue.size();
    }

    // ---- submit ---------------------------------------------------------------------

    public SubmitOutcome submit(PlatformUser user, String chatId, String rawUrl,
                                QualityPreset quality, Locale locale) {

        URI url;

        try {
            url = intake.validate(rawUrl);
        } catch (UrlIntake.InvalidUrlException e) {
            return new SubmitOutcome.InvalidUrl(e.getMessage());
        }

        SourceCatalog catalog = catalogHolder.catalog();
        Source source = matcher.match(url.toString());

        if (catalog.isDisabled(source.id())) {

            return new SubmitOutcome.Refused(Rejection.SOURCE_DISABLED,
                    Map.of("title", source.title()));

        }

        Rejection dailyQuota = checkDailyQuotas(user);

        if (dailyQuota != null) {
            return new SubmitOutcome.Refused(dailyQuota, quotaParams(dailyQuota));
        }

        long reservation = Math.min(downloader.maxFileBytes(),
                sizePolicy.maxDownloadBytes(user.platform()));

        // The cache path needs no reservation: its files already live in the vault.
        // The key carries the quality (and output-shaping options): a cached "best"
        // video must never answer an "/save audio" request for the same URL.
        ResultCache.Entry cached = resultCache.get(
                ResultCache.key(url.toString(), quality,
                        downloader.writeSubs(), downloader.audioThumbnail()));

        String jobId = TraceId.newId();
        DownloadJob job = new DownloadJob(jobId, user, chatId, url.toString(),
                quality, locale, clock.instant());

        Set<DownloadJob> userJobs = activeByUser.computeIfAbsent(user,
                k -> ConcurrentHashMap.newKeySet());

        // ATOMIC admission: the concurrency check and the slot claim happen under the
        // same monitor. The old check-then-add let N parallel submits from one user
        // all see "1 of 2 slots used" and all pass (a concurrency test caught 6 of 8
        // racers admitted against a cap of 2).
        synchronized (userJobs) {

            userJobs.removeIf(DownloadJob::isTerminal);

            if (userJobs.size() >= downloader.perUserConcurrent()) {

                return new SubmitOutcome.Refused(Rejection.CONCURRENT_LIMIT,
                        Map.of("count", downloader.perUserConcurrent()));

            }

            userJobs.add(job);

        }

        // From here on, every refusal path must undo BOTH the slot claim and (once
        // taken) the vault reservation - a finally block owns the slot, explicit
        // unreserves own the bytes.
        boolean accepted = false;
        boolean reserved = false;

        try {

            if (cached == null) {

                if (!vault.reserve(reservation)) {
                    return new SubmitOutcome.Refused(Rejection.VAULT_FULL);
                }

                reserved = true;

            }

            // Quota-safe insert: the hourly check is INSIDE the statement - two
            // parallel submits cannot both slip under the cap.
            boolean inserted;

            try {

                inserted = jobs.insertQueuedIfUnderHourQuota(jobId, user, url.toString(),
                        job.createdAt(), clock.instant().minus(Duration.ofHours(1)),
                        limits.jobsPerUserPerHour());

            } catch (RuntimeException e) {

                if (reserved) {
                    vault.unreserve(reservation);
                }

                throw e;

            }

            if (!inserted) {

                if (reserved) {
                    vault.unreserve(reservation);
                }

                return new SubmitOutcome.Refused(Rejection.QUOTA_HOUR,
                        quotaParams(Rejection.QUOTA_HOUR));

            }

            if (cached != null) {

                // Cache hits ride the SAME queue as fresh downloads: a storm of
                // repeated links must not deliver around the worker limit.
                job.attachCached(cached.jobId(), cached);

            }

            if (!queue.offer(job)) {

                jobs.updateFinished(jobId, "FAILED", Rejection.QUEUE_FULL.name(), null,
                        0, 0, clock.instant(), 0);

                if (reserved) {
                    vault.unreserve(reservation);
                }

                return new SubmitOutcome.Refused(Rejection.QUEUE_FULL);

            }

            accepted = true;

            if (cached == null) {
                sendAck(job, source, url);
            }

            return new SubmitOutcome.Accepted(jobId);

        } finally {

            if (!accepted) {
                userJobs.remove(job);
            }

        }

    }

    private @Nullable Rejection checkDailyQuotas(PlatformUser user) {

        // Best-effort read: the hard race-free cap is the hourly one inside the INSERT.
        UsageRepository.DayUsage today = usage.get(user, epochDay());

        if (today.jobs() >= limits.jobsPerUserPerDay()) {
            return Rejection.QUOTA_DAY;
        }

        if (today.bytes() >= limits.bytesPerUserPerDay()) {
            return Rejection.QUOTA_BYTES;
        }

        return null;

    }

    private Map<String, Object> quotaParams(Rejection rejection) {

        return switch (rejection) {
            case QUOTA_HOUR -> Map.of("limit", limits.jobsPerUserPerHour());
            case QUOTA_DAY -> Map.of("limit", limits.jobsPerUserPerDay());
            case QUOTA_BYTES -> Map.of(
                    "limit", ByteFormat.human(limits.bytesPerUserPerDay()));
            default -> Map.of();
        };

    }

    private void sendAck(DownloadJob job, Source source, URI url) {

        String host = url.getHost() == null ? source.title() : url.getHost();

        OutboundMessage.Send ack = replies.jobQueued(
                job.platform(), job.chatId(), job.locale(), host);

        dispatcher.submit(ack).whenComplete((result, error) -> {

            if (error != null) {

                log.debug("Status ack failed for job {} ({}): {}",
                        job.id(), job.user().key(), error.getMessage());
                return;

            }

            SendResult sent = result;

            if (sent != null && sent.messageId() != null) {
                job.setStatusMessageId(sent.messageId());
            }

        });

    }

    /** Cancels every non-terminal job of the user in the chat. @return how many. */
    public int cancel(PlatformUser user, String chatId) {

        Set<DownloadJob> userJobs = activeByUser.get(user);

        if (userJobs == null) {
            return 0;
        }

        int cancelled = 0;

        for (DownloadJob job : userJobs) {

            if (!chatId.equals(job.chatId()) || job.isTerminal()) {
                continue;
            }

            if (job.cancel()) {
                cancelled++;
            }

        }

        return cancelled;

    }

    // ---- cache (C41: dedupe within the vault grace window) ---------------------------

    private void serveFromCache(DownloadJob job, Source source, ResultCache.Entry cached) {

        metrics.increment("downloads_total", "result", "cache_hit", "source", source.id());

        jobs.updateStarted(job.id(), source.id(), clock.instant());

        int delivered = deliver(job, source.title(), cached.items(), cached.title(),
                cached.webpageUrl(), false, cached.backendId());

        Instant now = clock.instant();

        job.markSucceeded(delivered, cached.items().stream()
                .mapToLong(ExtractedItem::sizeBytes).sum(), now);

        jobs.updateFinished(job.id(), "SUCCEEDED", null, "cache", delivered,
                job.bytesDownloaded(), now, job.durationMillis());

        usage.increment(job.user(), epochDay(), 1, job.bytesDownloaded());

        // Refresh the grace window: the files just got sent again.
        vault.release(cached.jobId());

        dispatcher.submit(replies.jobDone(job.platform(), job.chatId(), null,
                job.locale(), delivered, job.bytesDownloaded(), Duration.ofSeconds(0), null));

    }

    // ---- worker ---------------------------------------------------------------------

    private void workerLoop() {

        while (running.get() || !queue.isEmpty()) {

            DownloadJob job;

            try {
                job = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (job == null) {
                continue;
            }

            if (job.isCancelRequested()) {
                finishCancelled(job);
                continue;
            }

            activeCount.incrementAndGet();

            try {
                runJob(job);
            } catch (Throwable t) {
                // The loop must survive everything: a dead worker is a silently
                // shrinking pool, and the queue would grow until submit() refuses all.
                log.error("Job worker crashed on {}", job.id(), t);
                failJob(job, ExtractionException.Category.UNKNOWN, t.getMessage());
            } finally {
                activeCount.decrementAndGet();
            }

        }

    }

    private void runJob(DownloadJob job) {

        TraceId.put(job.id());
        Instant started = clock.instant();

        DownloadJob.CachePayload payload = job.takeCached();

        if (payload != null) {

            ResultCache.Entry cached = resultCache.get(ResultCache.key(job.rawUrl(),
                    job.quality(), downloader.writeSubs(), downloader.audioThumbnail()));

            if (cached != null && cached.jobId().equals(payload.jobId())) {

                Source cachedSource = catalogHolder.catalog()
                        .find(matcher.match(job.rawUrl()).id())
                        .orElse(SourceCatalog.GENERIC);

                job.markRunning(Thread.currentThread(), started);
                serveFromCache(job, cachedSource, cached);

                return;

            }

            // The entry expired between submit and the worker: fall through and
            // download for real (no reservation was taken - take one now).
        }

        long reservation = Math.min(downloader.maxFileBytes(),
                sizePolicy.maxDownloadBytes(job.platform()));
        boolean reservedHere = payload == null;   // submit() reserved unless this was a cache hit

        if (payload != null) {

            if (!vault.reserve(reservation)) {

                failJob(job, ExtractionException.Category.UNKNOWN,
                        "Vault quota exhausted while re-checking a cache miss");
                return;

            }

            reservedHere = true;

        }

        try {

            job.markRunning(Thread.currentThread(), started);

            SourceCatalog catalog = catalogHolder.catalog();
            Source source = catalog.find(matcher.match(job.rawUrl()).id())
                    .orElse(SourceCatalog.GENERIC);

            jobs.updateStarted(job.id(), source.id(), started);

            politeness(source);

            Path workDir = vault.createJobDir(job.id());

            ExtractionRequest request = new ExtractionRequest(
                    source, URI.create(job.rawUrl()), job.quality(), workDir,
                    reservation, downloader.maxItemsPerRequest(), progressListener(job),
                    cookiesAllowed(job.user()));

            ExtractionResult result;

            try {
                result = chain.extract(request);
            } catch (ExtractionException e) {

                // "Too large" is the ONE failure worth a second attempt: a 480p renditon
                // of the same video usually fits the platform cap and beats a bare link.
                if (e.category() != ExtractionException.Category.TOO_LARGE
                        || !canDowngrade(job.quality())) {
                    throw e;
                }

                log.info("Job {}: {} does not fit, retrying at 480p", job.id(), job.quality());
                metrics.increment("retry_downgrade_total", "from", job.quality().id());

                purgeDir(workDir);

                result = chain.extract(new ExtractionRequest(
                        source, URI.create(job.rawUrl()), QualityPreset.P480, workDir,
                        reservation, downloader.maxItemsPerRequest(),
                        progressListener(job), cookiesAllowed(job.user())));

            }

            int delivered = deliver(job, source.title(), result.items(), result.title(),
                    result.webpageUrl(), result.truncated(), result.backendId());

            resultCache.put(ResultCache.key(job.rawUrl(), job.quality(),
                    downloader.writeSubs(), downloader.audioThumbnail()), result, job.id());

            Instant finished = clock.instant();
            job.markSucceeded(delivered, result.totalBytes(), finished);

            jobs.updateFinished(job.id(), "SUCCEEDED", null, result.backendId(),
                    delivered, result.totalBytes(), finished,
                    Duration.between(started, finished).toMillis());

            usage.increment(job.user(), epochDay(), 1, result.totalBytes());

            metrics.increment("downloads_total",
                    "result", "ok", "source", source.id(), "backend", result.backendId());
            metrics.observe("download_seconds",
                    Duration.between(started, finished).toMillis() / 1000.0,
                    "source", source.id());
            metrics.add("downloaded_bytes_total", result.totalBytes(), "source", source.id());

            vault.release(job.id());

            dispatcher.submit(replies.jobDone(job.platform(), job.chatId(),
                    job.statusMessageId(), job.locale(), delivered,
                    result.totalBytes(), Duration.between(started, finished),
                    result.warningKey()));

        } catch (ExtractionException e) {

            if (e.category() == ExtractionException.Category.CANCELLED
                    || job.isCancelRequested()) {
                finishCancelled(job);
                return;
            }

            failJob(job, e.category(), e.getMessage());

        } catch (IOException e) {

            failJob(job, ExtractionException.Category.UNKNOWN,
                    "Vault failure: " + e.getMessage());

        } finally {

            if (reservedHere) {
                vault.unreserve(reservation);
            }

            TraceId.clear();

        }

    }

    private static boolean canDowngrade(QualityPreset quality) {

        return quality == QualityPreset.BEST || quality == QualityPreset.P1080
                || quality == QualityPreset.P720;

    }

    /** Empties a work dir between attempts (the retry must not inherit partial files). */
    private static void purgeDir(Path dir) throws IOException {

        try (var stream = Files.walk(dir)) {

            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {

                if (!path.equals(dir)) {
                    Files.deleteIfExists(path);
                }

            }

        }

    }

    /**
     * Per-source politeness: a token bucket per catalog source bounds how often the
     * bot KNOCKS on one site (yt-dlp then makes its own requests inside the job, with
     * --sleep-requests softening those).
     */
    private void politeness(Source source) {

        if (source.isGeneric() || downloader.perSourcePerSecond() <= 0) {
            return;
        }

        TokenBucket bucket = sourceBuckets.computeIfAbsent(source.id(),
                id -> new TokenBucket(downloader.perSourcePerSecond(), 1.0));

        long waitNanos = bucket.reserveNanos();

        if (waitNanos > 0) {

            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        }

    }

    private boolean cookiesAllowed(PlatformUser user) {

        return !downloader.cookiesOwnerOnly() || config.ownerKeys().contains(user.key());

    }

    /**
     * Delivers items under the platform's size policy and the malware blocklist.
     * Multiple files go as ONE album where the platform supports it (Telegram), and as
     * individual sends everywhere else - the dispatcher expands groups by itself.
     *
     * @return how many files were actually handed to the platform.
     */
    private int deliver(DownloadJob job, String sourceTitle, List<ExtractedItem> items,
                        @Nullable String title, @Nullable String webpageUrl,
                        boolean truncated, String backendId) {

        List<MediaAttachment> ready = new ArrayList<>();
        int total = items.size();
        int index = 0;
        int blocked = 0;

        for (ExtractedItem item : items) {

            index++;

            if (isBlockedExtension(item.file())) {

                blocked++;
                metrics.increment("deliveries_blocked_total", "backend", backendId);

                dispatcher.submit(replies.blockedType(job.platform(), job.chatId(),
                        job.locale(), item.file().getFileName().toString()));

                continue;

            }

            vault.accountFor(item.sizeBytes());

            SizePolicy.Verdict verdict = sizePolicy.check(
                    job.platform(), item.kind(), item.sizeBytes());

            if (verdict == SizePolicy.Verdict.LINK_FALLBACK
                    || verdict == SizePolicy.Verdict.REJECT) {

                String link = item.mediaUrl() != null ? item.mediaUrl()
                        : webpageUrl != null ? webpageUrl : job.rawUrl();

                if (verdict == SizePolicy.Verdict.LINK_FALLBACK) {

                    dispatcher.submit(replies.linkFallback(job.platform(), job.chatId(),
                            job.locale(), link, item.sizeBytes(),
                            sizePolicy.capFor(job.platform(), item.kind())));

                } else {

                    dispatcher.submit(replies.rejection(job.platform(), job.chatId(),
                            job.locale(), "message.error.too_large", Map.of(
                                    "size", ByteFormat.human(item.sizeBytes()),
                                    "cap", ByteFormat.human(
                                            sizePolicy.capFor(job.platform(), item.kind())))));

                }

                continue;

            }

            MediaKind kind = verdict == SizePolicy.Verdict.AS_DOCUMENT
                    ? MediaKind.DOCUMENT
                    : item.kind();

            MediaAttachment attachment = MediaAttachment.of(kind, item.file(),
                    title != null ? title : item.title(),
                    item.width(), item.height(), item.durationSeconds(),
                    item.mediaUrl() != null ? item.mediaUrl() : webpageUrl);

            // ffprobe fills what the page did not know: Viber refuses "video" messages
            // without a duration, Telegram players want real geometry. Best effort -
            // a probe failure leaves the attachment exactly as the extractor built it.
            if (mediaProbe.available() && attachment.durationSeconds() == null
                    && (kind == MediaKind.VIDEO || kind == MediaKind.GIF
                        || kind == MediaKind.AUDIO)) {

                MediaProbe.Probed probed = mediaProbe.probe(item.file());

                if (!probed.isEmpty()) {

                    attachment = attachment.withMissingMetadata(
                            probed.durationSeconds(), probed.width(), probed.height());

                }

            }

            // Viber fetches media by URL: publish a short-lived public link when the
            // webapp is public; the adapter falls back to text when publicUrl is null.
            if (mediaLinks != null && mediaLinks.publishing()) {

                attachment = mediaLinks.publish(item.file(), attachment.fileName(),
                                attachment.mimeType())
                        .map(attachment::withPublicUrl)
                        .orElse(attachment);

            }

            ready.add(attachment);

        }

        if (ready.isEmpty()) {
            return 0;
        }

        String itemTitle = title;

        if (ready.size() > 1 && supportsGroups(job.platform())) {

            for (int from = 0; from < ready.size(); from += MAX_GROUP_SIZE) {

                List<MediaAttachment> chunk =
                        ready.subList(from, Math.min(from + MAX_GROUP_SIZE, ready.size()));

                dispatcher.submit(new OutboundMessage.SendMediaGroup(
                        job.platform(), job.chatId(),
                        replies.caption(job.locale(), itemTitle, sourceTitle,
                                chunk.stream().mapToLong(MediaAttachment::sizeBytes).sum(),
                                null, from + 1, total, truncated),
                        groupKeyboard(chunk, job.locale()),
                        chunk, false));

            }

            return ready.size();

        }

        int sent = 0;

        for (MediaAttachment attachment : ready) {

            sent++;

            dispatcher.submit(new OutboundMessage.SendMedia(
                    job.platform(), job.chatId(),
                    replies.caption(job.locale(), attachment.title(), sourceTitle,
                            attachment.sizeBytes(), attachment.durationSeconds(),
                            sent, ready.size(), truncated),
                    attachment.sourceUrl() != null
                            ? replies.sourceLink(job.locale(), attachment.sourceUrl())
                            : InlineKeyboard.empty(),
                    attachment));

        }

        return sent;

    }

    private boolean supportsGroups(eu.neydev.saver.core.api.Platform platform) {
        return dispatcher.platformSupportsGroups(platform);
    }

    private InlineKeyboard groupKeyboard(List<MediaAttachment> chunk, Locale locale) {

        return chunk.get(0).sourceUrl() != null
                ? replies.sourceLink(locale, chunk.get(0).sourceUrl())
                : InlineKeyboard.empty();

    }

    /** The malware-channel guard: executables/scripts never ride out as documents. */
    private boolean isBlockedExtension(Path file) {

        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');

        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }

        return downloader.blockedExtensions().contains(name.substring(dot + 1));

    }

    private ProgressListener progressListener(DownloadJob job) {

        return new ProgressListener() {

            private volatile int lastPercent = -100;
            private volatile long lastEditMillis;

            private volatile int lastFilesDone;

            @Override
            public void onProgress(@Nullable Integer percent, @Nullable String stage,
                                   int itemsDone, int itemsTotal) {

                // Platforms that cannot edit would turn every progress tick into a NEW
                // message - for them the queued ack and the final result are enough.
                if (!dispatcher.platformSupportsEdits(job.platform())) {
                    return;
                }

                String messageId = job.statusMessageId();
                long now = System.currentTimeMillis();

                if (percent == null) {

                    // Gallery progress: file count is the only honest signal; the same
                    // 3s throttle keeps a 40-image album from machine-gunning edits.
                    if (itemsDone > lastFilesDone && messageId != null
                            && now - lastEditMillis >= 3_000) {

                        lastFilesDone = itemsDone;
                        lastEditMillis = now;

                        dispatcher.submit(replies.jobFilesProgress(job.platform(),
                                job.chatId(), messageId, job.locale(), itemsDone));

                    }

                    return;

                }

                // Edit at most every 3s and at least 10 points apart: platforms
                // throttle edits harder than sends, and jitter reads as spam.
                if (messageId == null || percent >= 100 || percent < lastPercent + 10
                        || now - lastEditMillis < 3_000) {
                    return;
                }

                lastPercent = percent;
                lastEditMillis = now;

                dispatcher.submit(replies.jobProgress(job.platform(), job.chatId(),
                        messageId, job.locale(), percent));

            }

            @Override
            public boolean cancelled() {
                return job.isCancelRequested();
            }

        };

    }

    private void failJob(DownloadJob job, ExtractionException.Category category,
                         @Nullable String detail) {

        Instant now = clock.instant();

        job.markFailed(category, detail, now);

        jobs.updateFinished(job.id(), "FAILED", category.id(), null,
                job.itemCount(), job.bytesDownloaded(), now, job.durationMillis());

        metrics.increment("downloads_total", "result", "failed", "category", category.id());

        log.info("Job {} failed [{}]: {}", job.id(), category, detail);

        dispatcher.submit(replies.jobFailed(job.platform(), job.chatId(),
                job.statusMessageId(), job.locale(), category));

        vault.purge(job.id());

    }

    private void finishCancelled(DownloadJob job) {

        Instant now = clock.instant();

        job.markCancelled(now);

        jobs.updateFinished(job.id(), "CANCELLED", null, null,
                0, 0, now, job.durationMillis());

        metrics.increment("downloads_total", "result", "cancelled");

        dispatcher.submit(replies.cancelled(job.platform(), job.chatId(),
                job.statusMessageId(), job.locale()));

        vault.purge(job.id());

    }

    private long epochDay() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toEpochDay();
    }

    /** Tool availability, for the honest "engine status" line in /admin and /healthz. */
    public String engineStatus() {

        List<String> parts = new ArrayList<>();
        parts.add("tools[" + tools.describe() + "]");
        parts.add("workers=" + downloader.workers());
        parts.add("active=" + activeCount.get());
        parts.add("queued=" + queue.size());
        parts.add("cache=" + resultCache.size());

        return String.join(" ", parts);

    }

    /** Backends the chain can actually run - for /healthz. */
    public Map<String, Boolean> backendAvailability() {
        return chain.availability();
    }

}
