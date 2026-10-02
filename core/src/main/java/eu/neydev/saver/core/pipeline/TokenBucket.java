package eu.neydev.saver.core.pipeline;

/**
 * A token bucket for global platform rate limiting.
 * {@link #reserveNanos()} returns how many nanoseconds to wait before a token is issued
 * (0 - can send immediately); the calling thread sleeps itself, the bucket is not blocked.
 */
public final class TokenBucket {

    private final double ratePerSecond;
    private final double maxTokens;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond, double burstSeconds) {
        this.ratePerSecond = ratePerSecond;
        this.maxTokens = Math.max(1.0, ratePerSecond * burstSeconds);
        this.tokens = this.maxTokens;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized long reserveNanos() {

        refill();

        if (tokens >= 1.0) {
            tokens -= 1.0;

            return 0L;
        }

        double deficit = 1.0 - tokens;
        long waitNanos = (long) (deficit / ratePerSecond * 1_000_000_000L);
        tokens = 0.0;

        return Math.max(1L, waitNanos);

    }

    private void refill() {

        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;

        if (elapsedSeconds > 0) {
            tokens = Math.min(maxTokens, tokens + elapsedSeconds * ratePerSecond);
            lastRefillNanos = now;
        }

    }

}

