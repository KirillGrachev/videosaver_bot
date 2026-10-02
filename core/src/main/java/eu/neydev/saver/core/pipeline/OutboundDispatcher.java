package eu.neydev.saver.core.pipeline;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Outbound dispatcher: a per-platform queue, a pool of virtual-thread workers,
 * a global token bucket + a sliding window per chat, honest retries:
 *
 * <ul>
 *   <li>429/flood-control - retry strictly after the platform's {@code retryAfter};</li>
 *   <li>transient failures - exponential backoff up to {@code maxAttempts};</li>
 *   <li>permanent errors (bot deleted, chat blocked) - no retries,
 *       the future completes exceptionally, the scheduler marks the day delivered,
 *       so as not to hammer a dead chat for a whole day.</li>
 * </ul>
 *
 * <p>The {@link #submit(OutboundMessage)} call returns a future: the scheduler
 * of reminders moves the persistent cursor ONLY after a successful ack.
 */
public final class OutboundDispatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);

    private record Pending(OutboundMessage message, int attempt, long notBeforeMillis,
                           CompletableFuture<SendResult> future) {
        Pending with(int newAttempt) {
            return new Pending(message, newAttempt, notBeforeMillis, future);
        }

        Pending delayedUntil(long millis) {
            return new Pending(message, attempt, millis, future);
        }
    }

    private final Map<Platform, PlatformAdapter> adapters = new EnumMap<>(Platform.class);
    private final Map<Platform, LinkedBlockingQueue<Pending>> queues = new EnumMap<>(Platform.class);
    private final Map<Platform, TokenBucket> globalBuckets = new EnumMap<>(Platform.class);
    private final Map<Platform, ChatWindow> chatWindows = new EnumMap<>(Platform.class);

    private final int workersPerPlatform;
    private final int maxAttempts;
    private final long backoffMillis;
    private final MetricsRegistry metrics;
    private volatile boolean running;
    private final Map<Platform, Thread[]> pools = new ConcurrentHashMap<>();
    /** Platforms whose rejection has already been reported - one line each, not one per message. */
    private final Set<Platform> rejectionReported = ConcurrentHashMap.newKeySet();

    public OutboundDispatcher(int workersPerPlatform, int maxAttempts, long backoffMillis,
                              double globalPerSecond, int perChatPerMinute,
                              MetricsRegistry metrics) {
        this.workersPerPlatform = workersPerPlatform;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
        this.metrics = metrics;

        for (Platform platform : Platform.values()) {
            queues.put(platform, new LinkedBlockingQueue<>());
            globalBuckets.put(platform, new TokenBucket(globalPerSecond, 1.0));
            chatWindows.put(platform, new ChatWindow(perChatPerMinute));
        }
    }

    public void register(PlatformAdapter adapter) {
        adapters.put(adapter.platform(), adapter);
        rejectionReported.remove(adapter.platform());
    }

    public void start() {

        running = true;

        for (Platform platform : Platform.values()) {

            if (!adapters.containsKey(platform)) {
                continue;
            }

            Thread[] pool = new Thread[workersPerPlatform];

            for (int i = 0; i < workersPerPlatform; i++) {
                pool[i] = Thread.ofVirtual()
                        .name("outbound-" + platform.id() + "-" + i)
                        .start(() -> workerLoop(platform));
            }

            pools.put(platform, pool);
            metrics.gauge("outbound_queue_size", () -> queues.get(platform).size(),
                    "platform", platform.id());

        }

    }

    public CompletableFuture<SendResult> submit(OutboundMessage message) {

        // Albums on platforms without native groups: expand HERE, in one place, so no
        // adapter ever sees a SendMediaGroup it did not declare support for.
        if (message instanceof OutboundMessage.SendMediaGroup group) {

            PlatformAdapter groupAdapter = adapters.get(group.platform());

            if (groupAdapter != null && !groupAdapter.supportsMediaGroups()) {
                return expandGroup(group);
            }

        }

        CompletableFuture<SendResult> future = new CompletableFuture<>();
        Platform platform = message.platform();

        if (!running || !queues.containsKey(platform) || !adapters.containsKey(platform)) {

            // Never silent: a caller that ignores the returned future (the command router
            // does) would otherwise lose the message without a single line in the log.
            metrics.increment("outbound_rejected_total", "platform", platform.id());

            if (rejectionReported.add(platform)) {
                log.warn("Outbound message for {} rejected: the platform is not registered "
                        + "with the dispatcher (or the dispatcher is stopped) - replies and "
                        + "reminders for it cannot be sent", platform.id());
            }

            future.completeExceptionally(
                    new PlatformException("Platform is not active: " + platform));

            return future;

        }

        queues.get(platform).offer(new Pending(message, 1, 0, future));
        return future;

    }

    private CompletableFuture<SendResult> expandGroup(OutboundMessage.SendMediaGroup group) {

        java.util.List<CompletableFuture<SendResult>> parts = new java.util.ArrayList<>();

        for (int i = 0; i < group.media().size(); i++) {

            // The caption and the buttons ride on the FIRST file only: N copies of the
            // same caption under every photo is exactly the spam this expansion avoids.
            parts.add(submit(new OutboundMessage.SendMedia(
                    group.platform(), group.chatId(),
                    i == 0 ? group.caption() : eu.neydev.saver.core.text.RichText.plain(""),
                    i == 0 ? group.keyboard() : eu.neydev.saver.core.api.InlineKeyboard.empty(),
                    group.media().get(i), group.silent(), group.traceId())));

        }

        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0]))
                .thenApply(done -> parts.get(0).join());

    }

    /**
     * Can messages to this platform be edited after sending? Unknown platforms answer
     * {@code true} - an unregistered platform fails at submit() anyway, and a wrong
     * "true" only costs one progress edit that the send itself will refuse.
     */
    public boolean platformSupportsEdits(Platform platform) {

        PlatformAdapter adapter = adapters.get(platform);

        return adapter == null || adapter.supportsEdits();

    }

    /** Native albums? Unknown platforms answer false - the safe default expands groups. */
    public boolean platformSupportsGroups(Platform platform) {

        PlatformAdapter adapter = adapters.get(platform);

        return adapter != null && adapter.supportsMediaGroups();

    }

    private void workerLoop(Platform platform) {

        PlatformAdapter adapter = adapters.get(platform);
        LinkedBlockingQueue<Pending> queue = queues.get(platform);
        TokenBucket bucket = globalBuckets.get(platform);
        ChatWindow window = chatWindows.get(platform);

        while (running || !queue.isEmpty()) {

            Pending pending;

            try {
                pending = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (pending == null) {
                continue;
            }

            process(adapter, bucket, window, queue, pending, platform);

        }

    }

    private void process(PlatformAdapter adapter, TokenBucket bucket, ChatWindow window,
                         LinkedBlockingQueue<Pending> queue, Pending pending, Platform platform) {

        try {

            long wait = pending.notBeforeMillis() - System.currentTimeMillis();

            if (wait > 0) {
                Thread.sleep(wait);
            }

            long bucketWaitNanos = bucket.reserveNanos();

            if (bucketWaitNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(bucketWaitNanos);
            }

            if ((pending.message() instanceof OutboundMessage.Send
                    || pending.message() instanceof OutboundMessage.SendMedia
                    || pending.message() instanceof OutboundMessage.SendMediaGroup)
                    && !window.tryAcquire(pending.message().chatId())) {
                requeue(queue, pending, 1_000);
                return;
            }

            long startedNanos = System.nanoTime();
            eu.neydev.saver.core.util.TraceId.put(pending.message().traceId());
            SendResult result;

            try {
                result = adapter.execute(pending.message());
            } finally {
                eu.neydev.saver.core.util.TraceId.clear();
            }

            metrics.observe("outbound_latency_seconds",
                    (System.nanoTime() - startedNanos) / 1e9, "platform", platform.id());
            metrics.increment("outbound_total", "platform", platform.id(), "result", "ok");

            pending.future().complete(result != null ? result : SendResult.ack());

        } catch (PlatformException.RateLimitedException e) {

            metrics.increment("outbound_total", "platform", platform.id(), "result", "rate_limited");

            if (pending.attempt() >= maxAttempts + 2) {
                fail(pending, platform, e);
            } else {
                requeue(queue, pending.with(pending.attempt() + 1),
                        e.retryAfterMillis());
            }

        } catch (PlatformException.PermanentDeliveryException e) {

            metrics.increment("outbound_total", "platform", platform.id(), "result", "permanent_fail");
            log.debug("Permanent delivery failure {}: {}", pending.message().chatId(), e.getMessage());
            pending.future().completeExceptionally(e);

        } catch (RuntimeException e) {

            metrics.increment("outbound_total", "platform", platform.id(), "result", "error");

            if (pending.attempt() >= maxAttempts) {
                fail(pending, platform, e);
            } else {

                long delay = backoffMillis * (1L << Math.min(pending.attempt() - 1, 6));
                log.warn("Send failure {} (attempt {}, trace {}), retry in {} ms: {}",
                        platform.id(), pending.attempt(), pending.message().traceId(),
                        delay, e.getMessage());
                requeue(queue, pending.with(pending.attempt() + 1), delay);

            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.future().completeExceptionally(e);
        }
    }

    private void requeue(LinkedBlockingQueue<Pending> queue, Pending pending, long delayMillis) {
        queue.offer(new Pending(pending.message(), pending.attempt(),
                System.currentTimeMillis() + delayMillis, pending.future()));
    }

    private void fail(Pending pending, Platform platform, Exception e) {

        log.error("Exhausted send attempts for {} to chat {} (trace {})",
                platform.id(), pending.message().chatId(), pending.message().traceId(), e);

        metrics.increment("outbound_total", "platform", platform.id(), "result", "exhausted");
        pending.future().completeExceptionally(e);

    }

    /**
     * Stopping WITHOUT draining the queues - a deliberate decision:
     * reminders are protected by a persistent cursor and delivery log
     * (an undelivered one repeats on the next tick, including after a restart),
     * and command replies are ephemeral: the user simply repeats the action.
     * Draining instead would add a window of "the bot no longer replies but still sends old ones".
     */
    /**
     * Stop accepting, then give the queues {@code grace} to drain before the hard
     * interrupt: shutdown must not swallow the final "done" message of a download
     * that finished one second before SIGTERM.
     */
    public void closeGracefully(java.time.Duration grace) {

        running = false;

        long deadline = System.currentTimeMillis() + Math.max(0, grace.toMillis());

        while (System.currentTimeMillis() < deadline) {

            boolean empty = queues.values().stream()
                    .allMatch(queue -> queue.isEmpty());

            if (empty) {
                break;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

        }

        close();

    }

    @Override
    public void close() {

        running = false;
        pools.values().forEach(pool -> {
            for (Thread thread : pool) {
                thread.interrupt();
            }
        });

    }

    /** A sliding per-minute window per chat (anti-flood at the dialog level). */
    private static final class ChatWindow {

        private final int limitPerMinute;
        private final Map<String, long[]> windows = new ConcurrentHashMap<>();

        private ChatWindow(int limitPerMinute) {
            this.limitPerMinute = limitPerMinute;
        }

        private synchronized boolean tryAcquire(String chatId) {

            long minute = System.currentTimeMillis() / 60_000;
            long[] window = windows.computeIfAbsent(chatId, k -> new long[]{minute, 0});

            if (window[0] != minute) {
                window[0] = minute;
                window[1] = 0;
            }

            if (window[1] >= limitPerMinute) {
                return false;
            }

            window[1]++;

            if (windows.size() > 50_000) {
                windows.entrySet().removeIf(entry -> entry.getValue()[0] != minute);
            }

            return true;

        }

    }

}

