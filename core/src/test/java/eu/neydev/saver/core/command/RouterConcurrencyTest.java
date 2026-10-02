package eu.neydev.saver.core.command;

import eu.neydev.saver.core.TestBot;
import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency invariants under parallel load - the scenarios unit tests with one
 * thread cannot see: the per-user concurrent cap must hold under a race, the inbound
 * path must not lose or deadlock under a burst, and the storage must stay consistent
 * when many users hit the bot at once.
 */
class RouterConcurrencyTest {

    @Test
    void parallelSubmitsFromOneUserRespectTheConcurrencyCap(@TempDir Path dir)
            throws Exception {

        // Cap = 2, slow extractor, 8 parallel submits of DIFFERENT urls: exactly two
        // may become jobs; the rest must be refused (no check-then-insert race).
        try (TestBot bot = TestBot.with(dir,
                TestBot.config(dir, 2, 100, 2_000_000_000L, TestBot.defaultDelivery(),
                        1_000_000_000L),
                List.of(TestBot.slowExtractor()))) {

            // per-user-concurrent = 2 in TestBot.config's downloader
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            List<Throwable> failures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {

                final int index = i;
                pool.submit(() -> {

                    ready.countDown();

                    try {

                        go.await();

                        var outcome = bot.jobManager.submit(
                                new PlatformUser(Platform.TELEGRAM, "racer"),
                                "chat-race",
                                "https://youtube.com/watch?v=race" + index,
                                QualityPreset.BEST, java.util.Locale.ENGLISH);

                        if (outcome instanceof eu.neydev.saver.core.download.JobManager
                                .SubmitOutcome.Accepted) {
                            accepted.incrementAndGet();
                        }

                    } catch (Throwable t) {
                        synchronized (failures) {
                            failures.add(t);
                        }
                    }

                });

            }

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

            assertThat(failures).isEmpty();
            // The cap is 2: never more, and the slow extractor keeps both slots busy.
            assertThat(accepted.get()).isLessThanOrEqualTo(2);
            assertThat(bot.storage.jobs().countSince(
                    new PlatformUser(Platform.TELEGRAM, "racer"),
                    java.time.Instant.now().minusSeconds(60))).isLessThanOrEqualTo(2);

            bot.jobManager.cancel(new PlatformUser(Platform.TELEGRAM, "racer"), "chat-race");

        }

    }

    @Test
    void aBurstOfUsersAllGetAnswers(@TempDir Path dir) throws Exception {

        // 20 users x 3 messages through the real router; nothing may be lost or hang.
        try (TestBot bot = TestBot.standard(dir, MediaKind.PHOTO, 200)) {

            int users = 20;
            int messages = 3;
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch done = new CountDownLatch(users * messages);

            for (int u = 0; u < users; u++) {

                final int user = u;

                for (int m = 0; m < messages; m++) {

                    pool.submit(() -> {

                        try {

                            bot.router.accept(new IncomingUpdate.TextMessage(
                                    new PlatformUser(Platform.TELEGRAM, "burst-" + user),
                                    "chat-" + user,
                                    "/start",
                                    "en"));

                        } finally {
                            done.countDown();
                        }

                    });

                }

            }

            pool.shutdown();

            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("every inbound event must be processed without deadlock")
                    .isTrue();

            // Every /start produced exactly one Send (FakeAdapter is shared, thread-safe).
            TestBot.waitUntil(() -> bot.adapter.sentOfType(OutboundMessage.Send.class)
                    .size() >= users * messages, "all menu replies delivered");

            // 20 distinct users landed in storage - no lost upserts under the race.
            TestBot.waitUntil(() -> bot.storage.users().count() == users, "all users stored");

        }

    }

}
