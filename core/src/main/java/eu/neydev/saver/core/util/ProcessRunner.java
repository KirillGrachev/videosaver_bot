package eu.neydev.saver.core.util;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Subprocess runner for the extraction tools (yt-dlp, gallery-dl, ffmpeg). Built for
 * long downloads, so it does what a naive {@code Process.waitFor} does not:
 *
 * <ul>
 *   <li>streams stdout LINE BY LINE to a consumer - that is how download progress
 *       reaches the user instead of arriving all at once when the process exits;</li>
 *   <li>enforces a hard timeout with {@code destroyForcibly} - a hung extractor must
 *       not hold a worker slot forever;</li>
 *   <li>honours thread interruption: cancelling a job interrupts the worker, and the
 *       interrupted runner kills the child instead of leaking it;</li>
 *   <li>polls an ABORT condition while the process runs - that is how the download
 *       size watchdog kills an HLS stream that blew past {@code --max-filesize}
 *       (yt-dlp cannot enforce the cap on segmented formats by itself).</li>
 * </ul>
 *
 * <p>Only the TAIL of each stream is kept (last {@value #TAIL_LINES} lines): enough for
 * honest error classification, small enough to log.
 */
public final class ProcessRunner {

    private static final int TAIL_LINES = 200;
    private static final long POLL_MILLIS = 250;

    /** Exit code reported when the timeout, an interrupt or the abort flag killed the process. */
    public static final int KILLED = -1;

    /**
     * @param exitCode   process exit code, or {@link #KILLED} when we killed it;
     * @param timedOut   the timeout fired;
     * @param cancelled  the calling thread was interrupted (job cancelled);
     * @param aborted    the abort condition fired (size watchdog);
     * @param stdoutTail last lines of stdout;
     * @param stderrTail last lines of stderr - where yt-dlp/gallery-dl write their ERROR lines.
     */
    public record Result(int exitCode, boolean timedOut, boolean cancelled, boolean aborted,
                         List<String> stdoutTail, List<String> stderrTail) {

        public Result(int exitCode, boolean timedOut, boolean cancelled,
                      List<String> stdoutTail, List<String> stderrTail) {
            this(exitCode, timedOut, cancelled, false, stdoutTail, stderrTail);
        }

        public boolean success() {
            return exitCode == 0;
        }

        /** The last stderr line starting with ERROR, or the very last one - the useful bit. */
        public @Nullable String errorLine() {

            for (int i = stderrTail.size() - 1; i >= 0; i--) {
                if (stderrTail.get(i).startsWith("ERROR")) {
                    return stderrTail.get(i);
                }
            }

            return stderrTail.isEmpty() ? null : stderrTail.get(stderrTail.size() - 1);

        }

    }

    private final long timeoutMillis;

    public ProcessRunner(java.time.Duration timeout) {
        this.timeoutMillis = Math.max(1_000, timeout.toMillis());
    }

    /** Runs to completion without live progress. */
    public Result run(List<String> command, @Nullable Path workDir) {
        return run(command, workDir, null, null);
    }

    public Result run(List<String> command, @Nullable Path workDir,
                      @Nullable Consumer<String> onStdoutLine) {
        return run(command, workDir, onStdoutLine, null);
    }

    /**
     * Runs the command, feeding every stdout line to {@code onStdoutLine} and checking
     * {@code abort} between polls. Both may be null. The consumer must be cheap and
     * must not throw: it runs on the drain thread, and an escaping exception would
     * stall the pipe.
     */
    public Result run(List<String> command, @Nullable Path workDir,
                      @Nullable Consumer<String> onStdoutLine,
                      @Nullable BooleanSupplier abort) {

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);

        if (workDir != null) {
            builder.directory(workDir.toFile());
        }

        Process process;

        try {
            process = builder.start();
        } catch (IOException e) {
            // A missing binary surfaces here, not at exit: report it as a kill with the
            // OS message in stderr so the caller classifies TOOL_MISSING honestly.
            return new Result(KILLED, false, false,
                    List.of(), List.of("ERROR: cannot start " + command.get(0) + ": " + e.getMessage()));
        }

        List<String> stdoutTail = new ArrayList<>();
        List<String> stderrTail = new ArrayList<>();

        Thread outDrain = drain(process, true, stdoutTail, onStdoutLine);
        Thread errDrain = drain(process, false, stderrTail, null);

        try {

            long deadline = System.currentTimeMillis() + timeoutMillis;
            boolean finished = false;

            while (System.currentTimeMillis() < deadline) {

                finished = process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);

                if (finished) {
                    break;
                }

                if (abort != null && abort.getAsBoolean()) {

                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);

                    return new Result(KILLED, false, false, true,
                            tail(stdoutTail), tail(stderrTail));

                }

            }

            if (!finished) {

                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);

                return new Result(KILLED, true, false, tail(stdoutTail), tail(stderrTail));

            }

            outDrain.join(2_000);
            errDrain.join(2_000);

            return new Result(process.exitValue(), false, false,
                    tail(stdoutTail), tail(stderrTail));

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            process.destroyForcibly();

            return new Result(KILLED, false, true, tail(stdoutTail), tail(stderrTail));

        }

    }

    private static Thread drain(Process process, boolean stdout, List<String> sink,
                                @Nullable Consumer<String> consumer) {

        Thread thread = Thread.ofPlatform().daemon()
                .name(stdout ? "proc-out" : "proc-err")
                .start(() -> {

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            stdout ? process.getInputStream() : process.getErrorStream(),
                            StandardCharsets.UTF_8))) {

                        String line;

                        while ((line = reader.readLine()) != null) {

                            synchronized (sink) {

                                sink.add(line);

                                if (sink.size() > TAIL_LINES) {
                                    sink.remove(0);
                                }

                            }

                            if (consumer != null && stdout) {

                                try {
                                    consumer.accept(line);
                                } catch (RuntimeException ignored) {
                                    // A broken progress consumer must never kill the download.
                                }

                            }

                        }

                    } catch (IOException ignored) {
                        // The process died mid-read: the exit code tells the story.
                    }

                });

        return thread;

    }

    private static List<String> tail(List<String> lines) {

        synchronized (lines) {
            return List.copyOf(lines);
        }

    }

}
