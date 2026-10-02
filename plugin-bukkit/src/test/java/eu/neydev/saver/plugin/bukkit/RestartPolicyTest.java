package eu.neydev.saver.plugin.bukkit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The restart budget: a crash loop must exhaust it, a stable process must never hit it. */
class RestartPolicyTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RestartPolicy policy = new RestartPolicy(now::get);

    @Test
    void backoffDoublesFromFiveSecondsToAMinute() {

        assertThat(policy.nextRestartMillis(1_000)).isEqualTo(5_000);
        assertThat(policy.nextRestartMillis(1_000)).isEqualTo(10_000);
        assertThat(policy.nextRestartMillis(1_000)).isEqualTo(20_000);
        assertThat(policy.nextRestartMillis(1_000)).isEqualTo(40_000);
        assertThat(policy.nextRestartMillis(1_000)).isEqualTo(60_000);

    }

    @Test
    void sixthCrashInsideTheWindowSpendsTheBudget() {

        for (int i = 0; i < RestartPolicy.MAX_RESTARTS; i++) {
            assertThat(policy.nextRestartMillis(1_000)).isPositive();
        }

        assertThat(policy.nextRestartMillis(1_000)).isEqualTo(-1);

    }

    @Test
    void crashesOutsideTheWindowAreForgotten() {

        for (int i = 0; i < RestartPolicy.MAX_RESTARTS; i++) {
            policy.nextRestartMillis(1_000);
        }

        now.addAndGet(RestartPolicy.WINDOW_MILLIS + 1);
        assertThat(policy.nextRestartMillis(1_000)).isEqualTo(5_000);

    }

    @Test
    void aLongLivedProcessResetsTheBudgetAndTheBackoff() {

        policy.nextRestartMillis(1_000);
        policy.nextRestartMillis(1_000);

        assertThat(policy.nextRestartMillis(RestartPolicy.HEALTHY_UPTIME_MILLIS)).isEqualTo(5_000);

    }

}
