package eu.neydev.saver.plugin.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Collapses a child-process log storm into readable records. A transient upstream
 * failure (Telegram answering getUpdates with a 502) makes the polling library retry
 * with exponential backoff, and EVERY attempt dumps an error header plus its stack:
 * dozens of near-identical records a minute, differing only in the backoff millis.
 * Forwarded line by line, a blip becomes a wall of log on the Minecraft host.
 *
 * <p>Records (a header line plus its stack continuations) are compared with digits
 * masked, so "retrying in 529 millis" equals "retrying in 972 millis". For identical
 * CONSECUTIVE records:
 *
 * <ul>
 *   <li>the first record of a series is emitted in full - the first case stays
 *       completely diagnosable;</li>
 *   <li>repeats are counted silently;</li>
 *   <li>every 25th repeat emits a progress line, so a genuine long outage keeps
 *       speaking in the log;</li>
 *   <li>when the series ends, ONE summary line gives the exact scale.</li>
 * </ul>
 *
 * <p>DEBUG records are never coalesced: silencing debug is logback's job, not the
 * pump's. A debug record between two storm attempts is a foreign record: it flushes
 * the pending summary and breaks the series, exactly like any other different line.
 */
final class RepeatCoalescer {

    /** Long storms keep speaking: every Nth identical repeat emits a progress line. */
    private static final int PROGRESS_EVERY = 25;

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** (level, line) sink: the pump's own emit, so levels stay the pump's business. */
    private final BiConsumer<Integer, String> sink;

    private final List<String> bufferLines = new ArrayList<>();
    private final List<Integer> bufferLevels = new ArrayList<>();
    private int recordLevel;

    private String seriesMask;
    private int seriesLevel;
    private int repeats;
    private int unreported;

    RepeatCoalescer(BiConsumer<Integer, String> sink) {
        this.sink = sink;
    }

    /** Feeds one child line at the level the pump classified it. */
    void accept(int level, String line) {

        if (!bufferLines.isEmpty() && BotProcess.isTraceContinuation(line)) {
            bufferLines.add(line);
            bufferLevels.add(level);
            return;
        }

        closeRecord();

        recordLevel = level;
        bufferLines.add(line);
        bufferLevels.add(level);

    }

    /** Ends the buffered record and any open series: called when a pipe closes. */
    void flush() {

        closeRecord();
        endSeries();

    }

    private void closeRecord() {

        if (bufferLines.isEmpty()) {
            return;
        }

        String masked = DIGITS.matcher(String.join("\n", bufferLines)).replaceAll("N");
        List<String> record = List.copyOf(bufferLines);
        List<Integer> levels = List.copyOf(bufferLevels);
        int level = recordLevel;

        bufferLines.clear();
        bufferLevels.clear();

        if (level == BotProcess.LEVEL_DEBUG) {
            endSeries();
            emit(record, levels);
            return;
        }

        if (masked.equals(seriesMask)) {

            repeats++;
            unreported++;

            if (repeats % PROGRESS_EVERY == 0) {

                sink.accept(level, "still failing: identical record number " + repeats);
                unreported = 0;

            }

            return;

        }

        endSeries();
        seriesMask = masked;
        seriesLevel = level;
        emit(record, levels);

    }

    private void endSeries() {

        if (unreported > 0) {
            sink.accept(seriesLevel,
                    "(the record above repeated " + unreported + " more times)");
        }

        seriesMask = null;
        repeats = 0;
        unreported = 0;

    }

    private void emit(List<String> record, List<Integer> levels) {

        for (int i = 0; i < record.size(); i++) {
            sink.accept(levels.get(i), record.get(i));
        }

    }

}
