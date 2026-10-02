package eu.neydev.saver.core.download;

import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceBackend;
import eu.neydev.saver.core.source.SourceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultCacheTest {

    private static final Source SOURCE = new Source("tube", "Tube", List.of("tube.example"),
            SourceBackend.YTDLP, SourceStatus.OK);

    private static ExtractionResult result(Path file) {

        return new ExtractionResult(SOURCE, "ytdlp",
                List.of(ExtractedItem.of(MediaKind.VIDEO, file)),
                "Title", null, "https://tube.example/v/1", false);

    }

    @Test
    void theKeyCarriesTheQualityAndOutputOptions() {

        String best = ResultCache.key("https://x/1", QualityPreset.BEST, false, false);
        String audio = ResultCache.key("https://x/1", QualityPreset.AUDIO, false, false);
        String subs = ResultCache.key("https://x/1", QualityPreset.BEST, true, false);
        String thumb = ResultCache.key("https://x/1", QualityPreset.AUDIO, false, true);

        // The regression this pins: "best" and "audio" for one URL are DIFFERENT results.
        assertThat(List.of(best, audio, subs, thumb))
                .doesNotHaveDuplicates();

        // A thumbnail flag on a video preset changes nothing in the output.
        assertThat(ResultCache.key("https://x/1", QualityPreset.BEST, false, true))
                .isEqualTo(best);

    }

    /** A clock the test moves by hand: expiry is about TIME, not about sleeping. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }

    }

    @Test
    void entriesExpireByTimeAndDieWithTheirFiles(@TempDir Path dir) throws IOException {

        Path file = dir.resolve("v.mp4");
        Files.write(file, new byte[]{1});

        MovableClock clock = new MovableClock(Instant.parse("2026-10-01T12:00:00Z"));
        ResultCache cache = new ResultCache(10, Duration.ofMinutes(10), clock);

        cache.put("k", result(file), "job-1");
        cache.put("gone", result(file), "job-2");

        assertThat(cache.get("k")).isNotNull();

        // Expired by time: the entry is invisible and purge removes it.
        clock.advance(Duration.ofMinutes(30));

        assertThat(cache.get("k")).isNull();
        cache.purgeExpired();
        assertThat(cache.size()).isZero();

        // Expired by the vault sweeping the file away.
        clock = new MovableClock(Instant.parse("2026-10-01T12:00:00Z"));
        cache = new ResultCache(10, Duration.ofMinutes(10), clock);
        cache.put("k", result(file), "job-1");
        Files.delete(file);

        assertThat(cache.get("k")).isNull();

    }

    @Test
    void overflowPurgesExpiredEntries(@TempDir Path dir) throws IOException {

        Path file = dir.resolve("v.mp4");
        Files.write(file, new byte[]{1});

        MovableClock clock = new MovableClock(Instant.parse("2026-10-01T12:00:00Z"));
        ResultCache cache = new ResultCache(2, Duration.ofMinutes(10), clock);

        cache.put("old1", result(file), "j1");
        cache.put("old2", result(file), "j2");

        // An hour later (ttl 10m) both are expired; the next put must purge them
        // instead of growing the map past its bound.
        clock.advance(Duration.ofHours(1));

        cache.put("new1", result(file), "j3");

        assertThat(cache.size()).isEqualTo(1);

    }

}
