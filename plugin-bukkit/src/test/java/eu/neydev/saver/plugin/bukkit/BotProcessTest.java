package eu.neydev.saver.plugin.bukkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supervisor is the only thing standing between a dead bot and a server that keeps
 * serving players with a mute save feature, so its observable contract is tested for
 * real: a child process is spawned (a shell script standing in for the JVM) and the log
 * lines, the heartbeat tracking and the exit-code handling are asserted.
 */
class BotProcessTest {

    @TempDir
    Path home;

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    /** A fake JVM: an executable script that ignores {@code -jar <name>} and does our bidding. */
    private String fakeJava(String body) throws IOException {

        Path script = home.resolve("fakejava-" + Files.list(home).count() + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        Files.writeString(home.resolve("saver-launcher.jar"), "not a jar, just a marker");

        return script.toString();

    }

    private BotProcess process(String javaBinary, boolean restartOnCrash, Recording log) {
        return new BotProcess(home, "saver-launcher.jar", javaBinary, restartOnCrash, 5,
                log, new RestartPolicy(clock::get), clock::get);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
    }

    @Test
    void childConsoleIsClassifiedByItsOwnLevel() throws Exception {

        String java = fakeJava(String.join("\n",
                "echo '12:00:00.000 INFO Bot - hello'",
                "echo '12:00:01.000 WARN Bot - careful' >&2",
                "echo 'WARNING: A terminally deprecated method in sun.misc.Unsafe' >&2",
                "echo '12:00:02.000 ERROR Bot - boom' >&2"));

        Recording log = new Recording();
        BotProcess process = process(java, false, log);

        process.start();
        await(() -> log.error.stream().anyMatch(line -> line.contains("boom")));
        await(() -> !log.info.isEmpty() && log.warn.size() >= 1);

        // The child's own levels are respected...
        assertThat(log.info).anyMatch(line -> line.contains("INFO Bot - hello"));
        assertThat(log.warn).anyMatch(line -> line.contains("WARN Bot - careful"));
        assertThat(log.error).anyMatch(line -> line.contains("ERROR Bot - boom"));
        // ...and a JVM notice on stderr stays informational instead of flooding WARN.
        assertThat(log.warn).noneMatch(line -> line.contains("sun.misc.Unsafe"));
        assertThat(log.info).anyMatch(line -> line.contains("sun.misc.Unsafe"));

        process.stop();

    }

    @Test
    void heartbeatLineIsTrackedAndStalenessIsDetected() throws Exception {

        String java = fakeJava(String.join("\n",
                "echo '[status] uptime=1s platforms=telegram:up users=3'",
                "sleep 1"));

        Recording log = new Recording();
        BotProcess process = process(java, false, log);

        process.start();
        await(() -> !process.lastHeartbeat().isEmpty());

        assertThat(process.lastHeartbeat()).contains("platforms=telegram:up");
        assertThat(process.heartbeatStale()).isFalse();

        // Sixteen minutes without a new beat: the process may still exist, the bot is hung.
        clock.addAndGet(16 * 60_000L);
        assertThat(process.heartbeatStale()).isTrue();

        process.stop();

    }

    @Test
    void nonZeroExitIsReportedAsCrashWithoutRestartWhenDisabled() throws Exception {

        String java = fakeJava("exit 3");
        Recording log = new Recording();
        BotProcess process = process(java, false, log);

        process.start();
        await(() -> log.warn.stream().anyMatch(line -> line.contains("crashed with exit code 3")));

        assertThat(log.warn).anyMatch(line -> line.contains("crashed with exit code 3"));
        assertThat(process.running()).isFalse();

    }

    @Test
    void stopEndsTheChildAndReportsIt() throws Exception {

        String java = fakeJava(String.join("\n",
                "trap 'exit 0' TERM",
                "sleep 30 &",
                "wait"));

        Recording log = new Recording();
        BotProcess process = process(java, false, log);

        process.start();
        await(process::running);
        assertThat(process.running()).isTrue();

        process.stop();

        assertThat(process.running()).isFalse();
        await(() -> log.info.stream().anyMatch(line -> line.contains("stopped")));
        assertThat(log.info).anyMatch(line -> line.contains("saver bot stopped"));

    }

    @Test
    void jvmFlagsFollowTheChildJdkVersion() {

        assertThat(BotProcess.jvmArgsFor(21)).isEmpty();
        assertThat(BotProcess.jvmArgsFor(22)).containsExactly("--enable-native-access=ALL-UNNAMED");
        assertThat(BotProcess.jvmArgsFor(24)).containsExactly(
                "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow");

    }

    @Test
    void childStartsWithTheSilenceFlagsOfItsOwnJdk() throws Exception {

        String java = fakeJava(String.join("\n",
                "if [ \"$1\" = \"-version\" ]; then echo 'openjdk version \"24.0.1\" 2025-04-15'; exit 0; fi",
                "echo \"ARGS: $*\"",
                "sleep 1"));

        Recording log = new Recording();
        BotProcess process = process(java, false, log);

        process.start();
        await(() -> log.info.stream().anyMatch(line -> line.contains("ARGS:")));

        assertThat(log.info).anyMatch(line -> line.contains("--enable-native-access=ALL-UNNAMED")
                && line.contains("--sun-misc-unsafe-memory-access=allow")
                && line.contains("-jar saver-launcher.jar"));

        process.stop();

    }

    @Test
    void heartbeatStaysBelowTheConsoleUntilAPlatformChangesState() throws Exception {

        String java = fakeJava(String.join("\n",
                "echo '[status] uptime=1s platforms=telegram:up users=1'",
                "echo '[status] uptime=6s platforms=telegram:up users=1'",
                "echo '[status] uptime=11s platforms=telegram:connecting users=1'",
                "sleep 1"));

        Recording log = new Recording();
        BotProcess process = process(java, false, log);

        process.start();
        await(() -> log.debug.stream().anyMatch(line -> line.contains("[status]"))
                && log.info.stream().filter(line -> line.contains("[status]")).count() >= 2);

        // The repeating beat stays a debug line...
        assertThat(log.debug).anyMatch(line -> line.contains("platforms=telegram:up"));
        // ...while both state changes (first report, then the drop to connecting) reach INFO.
        assertThat(log.info).anyMatch(line -> line.contains("platforms=telegram:connecting"));
        assertThat(log.info.stream().filter(line -> line.contains("[status]")).count()).isEqualTo(2);

        process.stop();

    }

    /** Collects what the supervisor would print into the server log. */
    private static final class Recording implements BotProcess.Log {

        private final List<String> info = new CopyOnWriteArrayList<>();
        private final List<String> warn = new CopyOnWriteArrayList<>();
        private final List<String> error = new CopyOnWriteArrayList<>();
        private final List<String> debug = new CopyOnWriteArrayList<>();

        @Override
        public void info(String line) {
            info.add(line);
        }

        @Override
        public void warn(String line) {
            warn.add(line);
        }

        @Override
        public void error(String line) {
            error.add(line);
        }

        @Override
        public void debug(String line) {
            debug.add(line);
        }

    }

}
