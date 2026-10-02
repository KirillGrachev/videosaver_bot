package eu.neydev.saver.launcher;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleEncodingTest {

    @Test
    void reexecOnlyForWindowsNonUtf8WithoutMarker() {

        assertThat(ConsoleEncoding.needsReexec("Windows 11", null, "cp866")).isTrue();
        assertThat(ConsoleEncoding.needsReexec("Windows 11", null, "UTF-8")).isFalse();
        assertThat(ConsoleEncoding.needsReexec("Windows 11", "1", "cp866")).isFalse();
        assertThat(ConsoleEncoding.needsReexec("Linux", null, "ANSI_X3.4-1968")).isFalse();
        assertThat(ConsoleEncoding.needsReexec("Mac OS X", null, "UTF-8")).isFalse();

    }

    @Test
    void recognizesUtf8Aliases() {

        assertThat(ConsoleEncoding.isUtf8("UTF-8")).isTrue();
        assertThat(ConsoleEncoding.isUtf8("utf8")).isTrue();
        assertThat(ConsoleEncoding.isUtf8("cp866")).isFalse();
        assertThat(ConsoleEncoding.isUtf8("windows-1251")).isFalse();
        assertThat(ConsoleEncoding.isUtf8(null)).isFalse();
        assertThat(ConsoleEncoding.isUtf8("no-such-charset")).isFalse();

    }

    @Test
    void reexecCommandCarriesEncodingFlagsAndArgs() {

        List<String> command = ConsoleEncoding.command(
                new File("dist" + File.separator + "saver-launcher.jar"),
                new String[]{"--debug"});

        assertThat(command.get(0)).endsWith("bin" + File.separator + javaExe());
        assertThat(command).containsSequence(
                "-Dsaver.utf8=1",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-jar");

        assertThat(command).endsWith("--debug");

    }

    private static String javaExe() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
    }

}

