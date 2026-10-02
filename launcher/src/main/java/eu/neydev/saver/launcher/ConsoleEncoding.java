package eu.neydev.saver.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UTF-8 in the Windows console without a manual {@code chcp}: old consoles work
 * in CP866/CP1251, which turns Cyrillic and emoji in the logs into mojibake.
 * If the JVM is started directly ({@code java -jar saver-launcher.jar}) and the output
 * still not UTF-8 - we switch the console code page and restart the process
 * with explicit {@code -Dfile.encoding/-Dstdout.encoding/-Dstderr.encoding}.
 *
 * <p>A repeated restart excludes the {@code -Dsaver.utf8=1} marker, which
 * {@link RuntimeProvisioner} passes into the child JVM on a runtime change.
 * The class is Java 8 compatible: the launcher must start on any machine.
 */
public final class ConsoleEncoding {

    static final String MARKER = "saver.utf8";

    private ConsoleEncoding() {
    }

    /**
     * @return {@code true} if the current process already writes UTF-8 to the console;
     *         {@code false} after a restart in a new JVM (the call does not return).
     */
    public static boolean ensureUtf8(String[] args) throws IOException, InterruptedException {

        if (!needsReexec(System.getProperty("os.name", ""),
                System.getProperty(MARKER),
                System.getProperty("stdout.encoding", Charset.defaultCharset().name()))) {
            return true;
        }

        System.out.println("[launcher] console is not UTF-8 - switching (chcp 65001) and restarting");
        switchConsoleToUtf8();
        reexec(args);

        return false;

    }

    /** A reboot is needed only on Windows, only once and only when the output is not UTF-8. */
    static boolean needsReexec(String osName, String marker, String stdoutEncoding) {

        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }

        if ("1".equals(marker)) {
            return false;
        }

        return !isUtf8(stdoutEncoding);

    }

    static boolean isUtf8(String charsetName) {

        if (charsetName == null) {
            return false;
        }

        try {
            return Charset.forName(charsetName).equals(Charset.forName("UTF-8"));
        } catch (RuntimeException e) {
            return false;
        }

    }

    /** The full restart command line: java + encoding flags + launcher jar + arguments. */
    static List<String> command(File launcherJar, String[] args) {

        List<String> command = new ArrayList<String>();
        command.add(System.getProperty("java.home") + File.separator + "bin"
                + File.separator + javaExecutable());
        command.addAll(jvmOptions());
        command.add("-jar");
        command.add(launcherJar.getAbsolutePath());

        for (String arg : args) {
            command.add(arg);
        }

        return command;

    }

    static List<String> jvmOptions() {

        List<String> options = new ArrayList<String>();
        options.add("-D" + MARKER + "=1");
        options.add("-Dfile.encoding=UTF-8");
        options.add("-Dstdout.encoding=UTF-8");
        options.add("-Dstderr.encoding=UTF-8");

        return options;

    }

    private static String javaExecutable() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
    }

    /** {@code chcp 65001} in the current console window; failure is not critical - the JVM flags fix the output anyway. */
    private static void switchConsoleToUtf8() {
        try {
            new ProcessBuilder("cmd", "/c", "chcp", "65001")
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void reexec(String[] args) throws IOException, InterruptedException {

        File launcherJar = new File(Launcher.jarDirectory(), "saver-launcher.jar");
        ProcessBuilder builder = new ProcessBuilder(command(launcherJar, args));
        builder.inheritIO();
        Process process = builder.start();
        System.exit(process.waitFor());

    }

}

