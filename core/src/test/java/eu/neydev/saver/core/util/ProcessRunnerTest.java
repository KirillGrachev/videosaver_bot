package eu.neydev.saver.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessRunnerTest {

    private static Path script(Path dir, String body) throws IOException {

        Path file = dir.resolve("tool.sh");
        Files.writeString(file, "#!/bin/sh\n" + body);
        file.toFile().setExecutable(true);

        return file;

    }

    @Test
    void capturesStdoutAndTheExitCode(@TempDir Path dir) throws IOException {

        ProcessRunner.Result result = new ProcessRunner(Duration.ofSeconds(10))
                .run(List.of(script(dir, "echo hello; echo world; exit 3").toString()), null);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.success()).isFalse();
        assertThat(result.stdoutTail()).contains("hello", "world");

    }

    @Test
    void streamsLinesToTheConsumerAsTheyArrive(@TempDir Path dir) throws IOException {

        List<String> seen = new ArrayList<>();

        new ProcessRunner(Duration.ofSeconds(10)).run(
                List.of(script(dir, "echo one; echo two").toString()), null, seen::add);

        assertThat(seen).containsExactly("one", "two");

    }

    @Test
    void timeoutKillsTheProcess(@TempDir Path dir) throws IOException {

        ProcessRunner.Result result = new ProcessRunner(Duration.ofMillis(300))
                .run(List.of(script(dir, "sleep 30").toString()), null);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(ProcessRunner.KILLED);

    }

    @Test
    void aThrowingConsumerDoesNotKillTheDownload(@TempDir Path dir) throws IOException {

        ProcessRunner.Result result = new ProcessRunner(Duration.ofSeconds(10)).run(
                List.of(script(dir, "echo line").toString()), null,
                line -> {
                    throw new IllegalStateException("broken progress handler");
                });

        assertThat(result.success()).isTrue();

    }

    @Test
    void missingBinaryIsReportedNotThrown(@TempDir Path dir) {

        ProcessRunner.Result result = new ProcessRunner(Duration.ofSeconds(5))
                .run(List.of(dir.resolve("does-not-exist").toString()), null);

        assertThat(result.exitCode()).isEqualTo(ProcessRunner.KILLED);
        assertThat(result.errorLine()).contains("cannot start");

    }

    @Test
    void theAbortConditionKillsTheProcess(@TempDir Path dir) throws IOException {

        // The size-watchdog mechanism: an external condition (checked between polls)
        // must terminate a long-running child.
        java.util.concurrent.atomic.AtomicBoolean abort =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread flipper = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            abort.set(true);
        });
        flipper.start();

        ProcessRunner.Result result = new ProcessRunner(Duration.ofSeconds(30))
                .run(List.of(script(dir, "sleep 30").toString()), null, null, abort::get);

        assertThat(result.aborted()).isTrue();
        assertThat(result.exitCode()).isEqualTo(ProcessRunner.KILLED);

    }

    @Test
    void stderrTailFeedsErrorClassification(@TempDir Path dir) throws IOException {

        ProcessRunner.Result result = new ProcessRunner(Duration.ofSeconds(10))
                .run(List.of(script(dir, "echo 'ERROR: [generic] something broke' >&2; exit 1")
                        .toString()), null);

        assertThat(result.errorLine()).startsWith("ERROR");

    }

}
