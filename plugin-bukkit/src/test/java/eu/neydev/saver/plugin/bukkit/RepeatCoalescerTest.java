package eu.neydev.saver.plugin.bukkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storm contract: an identical record repeating is spoken once, counted silently,
 * kept honest by a progress line every 25th repeat and closed by ONE summary line -
 * while debug records and foreign records pass through untouched.
 */
class RepeatCoalescerTest {

    private final List<String> out = new ArrayList<>();

    private RepeatCoalescer coalescer() {
        return new RepeatCoalescer((level, line) -> out.add(level + "|" + line));
    }

    /** One getUpdates retry record: an ERROR header plus its stack continuations. */
    private static void stormRecord(RepeatCoalescer coalescer, int millis) {

        coalescer.accept(BotProcess.LEVEL_ERROR, "12:00:00.000 ERROR BotSession - Error "
                + "received from Telegram GetUpdates Request, retrying in " + millis + " millis...");
        coalescer.accept(BotProcess.LEVEL_ERROR,
                "    at org.telegram.telegrambots.longpolling.BotSession.getUpdates(BotSession.java:161)");
        coalescer.accept(BotProcess.LEVEL_ERROR,
                "Caused by: 502: TelegramApiErrorResponseException");
        coalescer.accept(BotProcess.LEVEL_ERROR, "    ... 7 common frames omitted");

    }

    @Test
    void stormCoalescesIntoFirstRecordAndOneSummary() {

        RepeatCoalescer coalescer = coalescer();

        stormRecord(coalescer, 529);
        stormRecord(coalescer, 972);
        stormRecord(coalescer, 1681);
        coalescer.flush();

        // The first case in full (4 lines), the scale in one summary - and nothing
        // about the two silenced repeats except the summary count.
        assertThat(out).hasSize(5);
        assertThat(out.get(0)).contains("retrying in 529 millis");
        assertThat(out.get(4)).contains("(the record above repeated 2 more times)");
        assertThat(String.join("\n", out)).doesNotContain("972").doesNotContain("1681");

    }

    @Test
    void foreignRecordBreaksTheSeriesAndFlushesBeforeItself() {

        RepeatCoalescer coalescer = coalescer();

        stormRecord(coalescer, 529);
        stormRecord(coalescer, 972);
        coalescer.accept(BotProcess.LEVEL_INFO, "12:00:05.000 INFO Bot - back to work");
        coalescer.flush();

        assertThat(out).hasSize(6);
        assertThat(out.get(4)).contains("(the record above repeated 1 more times)");
        assertThat(out.get(5)).contains("back to work");

    }

    @Test
    void debugRecordsAreNeverCoalesced() {

        RepeatCoalescer coalescer = coalescer();

        String heartbeat = "12:00:00.000 DEBUG StatusHeartbeat - [status] uptime=1s";
        coalescer.accept(BotProcess.LEVEL_DEBUG, heartbeat);
        coalescer.accept(BotProcess.LEVEL_DEBUG, heartbeat);
        coalescer.flush();

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isEqualTo(out.get(1));
        assertThat(String.join("\n", out)).doesNotContain("repeated");

    }

    @Test
    void longStormSpeaksEveryTwentyFifthRepeat() {

        RepeatCoalescer coalescer = coalescer();

        for (int i = 1; i <= 27; i++) {
            stormRecord(coalescer, 500 + i);
        }

        coalescer.flush();

        // 27 records = 26 repeats: progress speaks at repeat 25, the 26th lands in the
        // summary. First record (4 lines) + progress + summary.
        assertThat(out).hasSize(6);
        assertThat(out.get(4)).contains("still failing: identical record number 25");
        assertThat(out.get(5)).contains("(the record above repeated 1 more times)");

    }

}
