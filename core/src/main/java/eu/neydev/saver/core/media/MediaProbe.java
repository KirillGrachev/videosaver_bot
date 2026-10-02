package eu.neydev.saver.core.media;

import eu.neydev.saver.core.util.ProcessRunner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Fills metadata gaps with ffprobe: the OpenGraph scraper delivers files with NO
 * duration or dimensions, and platforms care - Viber refuses a "video" message without
 * a duration (it degrades to a file), Telegram renders videos better with real
 * geometry. Probing is best-effort: no ffprobe, a weird container or a timeout all
 * leave the metadata null, and the delivery proceeds with what the page told us.
 */
public final class MediaProbe {

    private static final Logger log = LoggerFactory.getLogger(MediaProbe.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public record Probed(@Nullable Integer durationSeconds,
                         @Nullable Integer width,
                         @Nullable Integer height) {

        public static final Probed EMPTY = new Probed(null, null, null);

        public boolean isEmpty() {
            return durationSeconds == null && width == null && height == null;
        }

    }

    private final @Nullable String ffprobeCommand;

    /** @param ffprobeCommand resolved command, or null when ffprobe is missing. */
    public MediaProbe(@Nullable String ffprobeCommand) {
        this.ffprobeCommand = ffprobeCommand;
    }

    public boolean available() {
        return ffprobeCommand != null;
    }

    /**
     * ffprobe -v error -select_streams v:0 -show_entries
     *   stream=width,height:format=duration -of csv=p=0 FILE
     * Output like "1920,1080\n12.345" (a video stream line, then the format line);
     * audio-only files produce just the duration line. Parsing is deliberately
     * tolerant: anything unparsable is simply "no metadata".
     */
    public Probed probe(Path file) {

        if (ffprobeCommand == null) {
            return Probed.EMPTY;
        }

        ProcessRunner.Result result = new ProcessRunner(TIMEOUT).run(List.of(
                ffprobeCommand,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height:format=duration",
                "-of", "csv=p=0",
                file.toString()), null);

        if (!result.success()) {
            return Probed.EMPTY;
        }

        Integer width = null;
        Integer height = null;
        Integer duration = null;

        for (String line : result.stdoutTail()) {

            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            String[] parts = trimmed.split(",");

            if (parts.length >= 2 && isInt(parts[0]) && isInt(parts[1])) {

                width = Integer.parseInt(parts[0].trim());
                height = Integer.parseInt(parts[1].trim());
                continue;

            }

            Double seconds = parseDouble(trimmed);

            if (seconds != null && seconds > 0) {
                duration = (int) Math.round(seconds);
            }

        }

        if (width != null && width == 0) {
            width = null;
        }

        if (height != null && height == 0) {
            height = null;
        }

        return new Probed(duration, width, height);

    }

    private static boolean isInt(String value) {

        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }

    }

    private static @Nullable Double parseDouble(String value) {

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }

    }

}
