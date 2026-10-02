package eu.neydev.saver.core.download;

import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.QualityPreset;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived result cache: the same URL requested again while its files still sit in
 * the vault (the send-grace window) is re-served from disk instead of hammering the
 * source a second time. Extracted out of the job manager so the cache policy (keys,
 * expiry, validation) is testable on its own.
 *
 * <p>Keys carry everything that changes the RESULT: the URL, the quality preset and
 * the output-shaping options. A cached "best" video must never answer an
 * "/save audio" request for the same link - that bug shipped once and is the reason
 * the key is a method, not a string concatenation at some call site.
 *
 * <p>Entries are validated on read (expiry + files still on disk): a swept vault must
 * never produce a cache hit that cannot be delivered.
 */
public final class ResultCache {

    private static final Logger log = LoggerFactory.getLogger(ResultCache.class);

    public record Entry(String jobId, String sourceTitle, String backendId,
                        @Nullable String title, @Nullable String webpageUrl,
                        List<ExtractedItem> items, Instant expiresAt) {
    }

    private final int maxEntries;
    private final java.time.Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ResultCache(int maxEntries, java.time.Duration ttl, Clock clock) {
        this.maxEntries = maxEntries;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** The canonical cache key - single definition, used on both put and get. */
    public static String key(String url, QualityPreset quality, boolean subs, boolean thumbnail) {

        StringBuilder sb = new StringBuilder(url).append('\u0000').append(quality.id());

        if (subs) {
            sb.append("\u0000subs");
        }

        if (quality == QualityPreset.AUDIO && thumbnail) {
            sb.append("\u0000thumb");
        }

        return sb.toString();

    }

    public @Nullable Entry get(String key) {

        Entry entry = entries.get(key);

        if (entry == null) {
            return null;
        }

        if (entry.expiresAt().isBefore(clock.instant()) || !filesAlive(entry)) {

            entries.remove(key);
            return null;

        }

        return entry;

    }

    public void put(String key, ExtractionResult result, String jobId) {

        if (entries.size() >= maxEntries) {
            purgeExpired();
        }

        entries.put(key, new Entry(jobId, result.source().title(), result.backendId(),
                result.title(), result.webpageUrl(), result.items(),
                clock.instant().plus(ttl)));

    }

    public int size() {
        return entries.size();
    }

    public void purgeExpired() {

        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));

    }

    private static boolean filesAlive(Entry entry) {
        return entry.items().stream().allMatch(item -> Files.isRegularFile(item.file()));
    }

}
