package eu.neydev.saver.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CookieFileGuardTest {

    private static boolean posix() {
        return java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix");
    }

    @Test
    void loosePermissionsAreReported(@TempDir Path dir) throws IOException {

        assumeTrue(posix(), "POSIX permissions only");

        Path cookies = dir.resolve("cookies.txt");
        Files.writeString(cookies, "netscape-cookie-file");
        Files.setPosixFilePermissions(cookies, PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(CookieFileGuard.warnIfLoose(cookies.toString())).isTrue();

    }

    @Test
    void tightPermissionsPass(@TempDir Path dir) throws IOException {

        assumeTrue(posix(), "POSIX permissions only");

        Path cookies = dir.resolve("cookies.txt");
        Files.writeString(cookies, "netscape-cookie-file");
        Files.setPosixFilePermissions(cookies,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        assertThat(CookieFileGuard.warnIfLoose(cookies.toString())).isFalse();

    }

    @Test
    void missingAndEmptyConfigAreNotWarnings(@TempDir Path dir) {

        assertThat(CookieFileGuard.warnIfLoose(null)).isFalse();
        assertThat(CookieFileGuard.warnIfLoose("  ")).isFalse();
        assertThat(CookieFileGuard.warnIfLoose(dir.resolve("nope.txt").toString())).isFalse();

    }

}
