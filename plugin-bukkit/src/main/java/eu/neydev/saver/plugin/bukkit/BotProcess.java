package eu.neydev.saver.plugin.bukkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The child-process supervisor: runs the standalone {@code saver-launcher.jar} from
 * a folder, pipes its stdout and stderr into the host log line by line and restarts it
 * on a crash within the {@link RestartPolicy} budget.
 *
 * <p>The class is plain JDK on purpose: no Bukkit types, no bot types. The plugin wraps
 * it for the server lifecycle, tests drive it directly, and the bot itself never learns
 * it runs inside a Minecraft server - the process boundary keeps both sides replaceable.
 */
public final class BotProcess {

    /** Where the child console goes; the plugin forwards it to the server logger. */
    public interface Log {

        void info(String line);

        void warn(String line);

        void error(String line);

        /** A line kept out of the default console: the periodic heartbeat, for instance. */
        void debug(String line);

    }

    /**
     * The child's own log level, read from its logback line prefix
     * ({@code HH:mm:ss.SSS LEVEL logger - msg}). Without it every JVM notice on stderr
     * (deprecated {@code sun.misc.Unsafe} calls, GC hints) lands in the server log as a
     * WARN and drowns the warnings that matter.
     */
    private static final Pattern CHILD_LEVEL = Pattern.compile(
            "^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s");

    /**
     * The heartbeat marker printed by the bot's StatusHeartbeat. Duplicated here on
     * purpose: this module is plain JDK and knows nothing about bot classes, the marker
     * is the contract between the two.
     */
    private static final String HEARTBEAT_MARKER = "[status]";

    /** A heartbeat older than this means the process is alive but the bot is hung. */
    private static final long STALE_HEARTBEAT_MILLIS = 15 * 60_000L;

    /** An exit code 0 this early is not a planned shutdown - the bot died silently. */
    private static final long SUSPICIOUSLY_SHORT_LIFE_MILLIS = 60_000L;

    private static final int LEVEL_DEBUG = -1;
    private static final int LEVEL_INFO = 0;
    private static final int LEVEL_WARN = 1;
    private static final int LEVEL_ERROR = 2;

    /** {@link #heartbeatLevel} result for a line that is not a heartbeat. */
    private static final int NOT_A_HEARTBEAT = -2;

    /** {@code java -version} first line: {@code openjdk version "24.0.1" 2025-04-15}. */
    private static final Pattern VERSION = Pattern.compile("\"(\\d+)(?:\\.\\d+)*\"");

    private final Path home;
    private final String launcherJar;
    private final String javaBinary;
    private final boolean restartOnCrash;
    private final long stopTimeoutSeconds;
    private final Log log;
    private final RestartPolicy restarts;
    private final LongSupplier millis;

    private final AtomicReference<Process> current = new AtomicReference<>();
    private volatile boolean supervisorStopped = true;
    private volatile long startedAtMillis;
    private volatile String lastHeartbeat = "";
    private volatile long lastHeartbeatMillis;
    private volatile String lastPlatformsSegment = "";
    private volatile int detectedFeature;

    public BotProcess(Path home,
                      String launcherJar,
                      String javaBinary,
                      boolean restartOnCrash,
                      long stopTimeoutSeconds,
                      Log log) {
        this(home, launcherJar, javaBinary, restartOnCrash, stopTimeoutSeconds, log, new RestartPolicy());
    }

    public BotProcess(Path home,
                      String launcherJar,
                      String javaBinary,
                      boolean restartOnCrash,
                      long stopTimeoutSeconds,
                      Log log,
                      RestartPolicy restarts) {
        this(home, launcherJar, javaBinary, restartOnCrash, stopTimeoutSeconds, log, restarts,
                System::currentTimeMillis);
    }

    /** Testable: the clock of heartbeat ages and uptimes is substituted. */
    BotProcess(Path home,
               String launcherJar,
               String javaBinary,
               boolean restartOnCrash,
               long stopTimeoutSeconds,
               Log log,
               RestartPolicy restarts,
               LongSupplier millis) {
        this.home = home;
        this.launcherJar = launcherJar;
        this.javaBinary = javaBinary;
        this.restartOnCrash = restartOnCrash;
        this.stopTimeoutSeconds = stopTimeoutSeconds;
        this.log = log;
        this.restarts = restarts;
        this.millis = millis;
    }

    public Path launcherJarPath() {
        return home.resolve(launcherJar);
    }

    public boolean running() {
        Process child = current.get();
        return child != null && child.isAlive();
    }

    public long pid() {
        Process child = current.get();
        return child == null ? -1 : child.pid();
    }

    public long uptimeSeconds() {
        return running() ? (millis.getAsLong() - startedAtMillis) / 1000 : 0;
    }

    public synchronized void start() {

        if (running()) {
            log.warn("saver bot is already running (pid " + pid() + ")");
            return;
        }

        if (!launcherJarPath().toFile().isFile()) {
            log.warn("no " + launcherJar + " in " + home.toAbsolutePath()
                    + " - copy the bot distribution there first");
            return;
        }

        supervisorStopped = false;
        spawn();

    }

    public synchronized void stop() {

        supervisorStopped = true;
        Process child = current.get();

        if (child == null) {
            return;
        }

        // `current` stays set on purpose: the waiter thread in exited() owns the bookkeeping
        // and is the single place that reports the exit. Nulling it here made exited() bail
        // out on its compare-and-set, and the "stopped" line was never logged at all.
        child.destroy();

        try {

            if (!child.waitFor(stopTimeoutSeconds, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                log.warn("saver bot did not exit in " + stopTimeoutSeconds + " s and was killed");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
        }

    }

    public synchronized void restart() {
        stop();
        start();
    }

    private void spawn() {

        try {

            List<String> command = new ArrayList<>();

            command.add(javaBinary);
            command.addAll(jvmArgs());
            command.add("-jar");
            command.add(launcherJar);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(home.toFile());

            Process child = builder.start();

            startedAtMillis = millis.getAsLong();
            current.set(child);
            pump(child.getInputStream(), "bot-stdout");
            pump(child.getErrorStream(), "bot-stderr");
            await(child);

            log.info("saver bot started (pid " + child.pid() + ", home "
                    + home.toAbsolutePath() + ")");

        } catch (IOException e) {
            log.warn("cannot start the saver bot: " + e);
        }
    }

    /**
     * Flags that keep the JDK's own startup notices out of the log: on JDK 22+ a native
     * library load (sqlite-jdbc) is reported as a restricted {@code System::load} call, and
     * on JDK 23+ the terminally deprecated {@code sun.misc.Unsafe} memory-access methods
     * (reached through Guava, a transitive dependency) print a four-line warning. Both are
     * noise: nothing is actually broken, yet on a shared host they read like a crash report.
     */
    List<String> jvmArgs() {
        return jvmArgsFor(detectFeatureVersion());
    }

    /** Per JDK release: older releases reject unknown flags and would not start at all. */
    static List<String> jvmArgsFor(int feature) {

        List<String> args = new ArrayList<>();

        if (feature >= 22) {
            args.add("--enable-native-access=ALL-UNNAMED");
        }

        if (feature >= 23) {
            args.add("--sun-misc-unsafe-memory-access=allow");
        }

        return args;

    }

    private int detectFeatureVersion() {

        int cached = detectedFeature;

        if (cached > 0) {
            return cached;
        }

        int feature = probeFeatureVersion(javaBinary);

        if (feature <= 0) {
            feature = Runtime.version().feature();
        }

        detectedFeature = feature;
        return feature;

    }

    /**
     * {@code java -version} once, at the first spawn: the child may run on a different JDK
     * than the server (the config picks the binary), so the server's own version proves
     * nothing. An unreadable probe falls back to the server version.
     */
    static int probeFeatureVersion(String javaBinary) {

        try {

            Process probe = new ProcessBuilder(javaBinary, "-version")
                    .redirectErrorStream(true).start();

            String first;

            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
                first = in.readLine();
            }

            probe.waitFor(5, TimeUnit.SECONDS);

            Matcher matcher = VERSION.matcher(first == null ? "" : first);

            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }

        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }

        return 0;

    }

    private void await(Process child) {

        Thread waiter = new Thread(() -> exited(child), "bot-process-waiter");

        waiter.setDaemon(true);
        waiter.start();

    }

    private void exited(Process child) {

        int code;

        try {
            code = child.waitFor();
        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            return;

        }

        if (!current.compareAndSet(child, null)) {
            return;
        }

        long uptimeMillis = millis.getAsLong() - startedAtMillis;

        if (supervisorStopped) {
            log.info("saver bot stopped (exit code " + code + " after "
                    + uptimeMillis / 1000 + " s)");
            return;
        }

        if (code == 0) {
            // The bot now exits non-zero on a fatal startup error, so a quick exit with 0
            // means something else ended it - worth a warning rather than a calm line.
            if (uptimeMillis < SUSPICIOUSLY_SHORT_LIFE_MILLIS) {
                log.warn("saver bot exited with code 0 after only " + uptimeMillis / 1000
                        + " s - a healthy bot runs until the server stops; check the [bot]"
                        + " lines above for the reason");
            } else {
                log.info("saver bot exited on its own with code 0 after "
                        + uptimeMillis / 1000 + " s");
            }
            return;
        }

        log.warn("saver bot crashed with exit code " + code + " after "
                + uptimeMillis / 1000 + " s");

        if (!restartOnCrash) {
            return;
        }

        long delay = restarts.nextRestartMillis(uptimeMillis);

        if (delay < 0) {
            log.warn("restart budget exhausted (" + RestartPolicy.MAX_RESTARTS
                    + " crashes in " + RestartPolicy.WINDOW_MILLIS / 60_000
                    + " min) - /saverbot start when the reason is fixed");
            return;
        }

        log.info("restarting the saver bot in " + delay / 1000 + " s");
        respawn(delay);

    }

    private void respawn(long millis) {

        Thread thread = new Thread(() -> {

            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            synchronized (this) {
                if (!supervisorStopped && !running()) {
                    spawn();
                }
            }

        }, "bot-process-restart");

        thread.setDaemon(true);
        thread.start();

    }

    /**
     * One pipe, one daemon thread: the child never blocks on a full OS buffer.
     *
     * <p>The level is read from the child's own log prefix. A line without one - a stack
     * trace continuation, a JVM notice on stderr - keeps the level of the line before it,
     * so an ERROR keeps its whole trace at ERROR while the JVM's deprecation warnings stay
     * informational instead of flooding the server log as WARN.
     */
    private void pump(InputStream stream, String threadName) {

        Thread thread = new Thread(() -> {

            int level = LEVEL_INFO;

            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {

                String line;

                while ((line = in.readLine()) != null) {

                    Matcher matcher = CHILD_LEVEL.matcher(line);

                    if (matcher.find()) {
                        level = levelOf(matcher.group(1));
                    } else if (!isTraceContinuation(line)) {
                        // An unprefixed line that is not part of a stack trace (a JVM notice,
                        // a launcher banner) is informational on its own. Keeping the previous
                        // level would promote every JVM warning to the level of the line above.
                        level = LEVEL_INFO;
                    }

                    int heartbeatLevel = heartbeatLevel(line);

                    if (heartbeatLevel == NOT_A_HEARTBEAT) {
                        emit(level, line);
                    } else {
                        emit(heartbeatLevel, line);
                    }

                }

            } catch (IOException e) {
                // the child is gone, the pipe is closed - nothing to report
            }

        }, threadName);

        thread.setDaemon(true);
        thread.start();

    }

    private void emit(int level, String line) {
        switch (level) {
            case LEVEL_ERROR -> log.error("[bot] " + line);
            case LEVEL_WARN -> log.warn("[bot] " + line);
            case LEVEL_DEBUG -> log.debug("[bot] " + line);
            default -> log.info("[bot] " + line);
        }
    }

    private static int levelOf(String name) {
        return switch (name) {
            case "ERROR" -> LEVEL_ERROR;
            case "WARN" -> LEVEL_WARN;
            default -> LEVEL_INFO;
        };
    }

    /** Stack-trace lines belong to the ERROR/WARN header above them, not to themselves. */
    private static boolean isTraceContinuation(String line) {
        return line.startsWith("\t") || line.startsWith("    at ") || line.startsWith("Caused by:")
                || line.matches("^\\s*\\.\\.\\. \\d+ more.*");
    }

    /**
     * Keeps the last heartbeat so {@code /saverbot status} can judge liveness, and tells
     * the pump how loud the line deserves to be: a heartbeat repeats every few minutes and
     * says nothing new, so it stays at DEBUG; the moment a platform changes state (up to
     * connecting, or back) the very same line becomes worth an INFO on the host console.
     *
     * @return {@link #NOT_A_HEARTBEAT}, or the level the line should be emitted at.
     */
    private int heartbeatLevel(String line) {

        int at = line.indexOf(HEARTBEAT_MARKER);

        if (at < 0) {
            return NOT_A_HEARTBEAT;
        }

        String summary = line.substring(at + HEARTBEAT_MARKER.length()).trim();
        String platforms = platformsSegment(summary);
        boolean stateChanged = !platforms.equals(lastPlatformsSegment);
        lastPlatformsSegment = platforms;
        lastHeartbeat = summary;
        lastHeartbeatMillis = millis.getAsLong();

        return stateChanged ? LEVEL_INFO : LEVEL_DEBUG;

    }

    /** The {@code platforms=...} segment: the part of the heartbeat a human acts on. */
    private static String platformsSegment(String summary) {

        int from = summary.indexOf("platforms=");

        if (from < 0) {
            return "";
        }

        int to = summary.indexOf(' ', from);
        return to < 0 ? summary.substring(from) : summary.substring(from, to);

    }

    /** The last heartbeat the bot printed, or an empty string before the first one. */
    public String lastHeartbeat() {
        return lastHeartbeat;
    }

    /** Seconds since the last heartbeat, or {@code -1} when there was none yet. */
    public long heartbeatAgeSeconds() {
        long at = lastHeartbeatMillis;
        return at == 0 ? -1 : (millis.getAsLong() - at) / 1000;
    }

    /**
     * Alive but silent for too long: the process exists, yet the bot stopped reporting -
     * a hung JVM looks exactly like this and a restart is the only remedy.
     */
    public boolean heartbeatStale() {
        long age = heartbeatAgeSeconds();
        return age >= 0 && age * 1000 > STALE_HEARTBEAT_MILLIS;
    }

}
