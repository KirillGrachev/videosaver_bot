package eu.neydev.saver.core.util;

import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Correlation id of a single update/reminder: put into MDC ("trace")
 * at the pipeline entry and reaches all logs and outbound messages of the chain.
 * Without it, tracing one update's path through the logs is impossible.
 */
public final class TraceId {

    private static final String MDC_KEY = "trace";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceId() {
    }

    public static String newId() {

        byte[] bytes = new byte[5];
        RANDOM.nextBytes(bytes);

        return HexFormat.of().formatHex(bytes);

    }

    public static void put(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static @Nullable String current() {
        return MDC.get(MDC_KEY);
    }

    public static String currentOrNew() {
        String current = MDC.get(MDC_KEY);
        return current == null ? newId() : current;
    }

}

