package eu.neydev.saver.core.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void burstThenThrottle() {

        TokenBucket bucket = new TokenBucket(2.0, 1.0); // 2 tokens, burst 1 second

        assertThat(bucket.reserveNanos()).isZero();
        assertThat(bucket.reserveNanos()).isZero();
        assertThat(bucket.reserveNanos()).isPositive();

    }

    @Test
    void refillsOverTime() throws Exception {

        TokenBucket bucket = new TokenBucket(100.0, 1.0);
        for (int i = 0; i < 100; i++) {
            assertThat(bucket.reserveNanos()).isZero();
        }

        Thread.sleep(50);
        assertThat(bucket.reserveNanos()).isZero();

    }

}

