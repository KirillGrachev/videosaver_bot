package eu.neydev.saver.core.security;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The cookies file is the operator's ACCOUNT: session tokens for every site it was
 * exported from. A world-readable cookies file on a shared host is a silent account
 * leak, and nothing in the extraction flow would ever surface it - so the check lives
 * here, at startup, as a loud warning (not a refusal: a read-only mount or an exotic
 * filesystem must not brick the bot).
 */
public final class CookieFileGuard {

    private static final Logger log = LoggerFactory.getLogger(CookieFileGuard.class);

    private CookieFileGuard() {
    }

    /**
     * @return true when the file is readable by group or others (POSIX) - tests assert
     *         on the verdict; the warning is a side effect for the operator's log.
     */
    public static boolean warnIfLoose(@Nullable String cookiesFile) {

        if (cookiesFile == null || cookiesFile.isBlank()) {
            return false;
        }

        Path path = Path.of(cookiesFile);

        if (!Files.isRegularFile(path)) {

            log.warn("downloader.cookies-file points at a missing file: {} "
                    + "(login-walled sources will refuse until it exists)", path);
            return false;

        }

        try {

            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);

            boolean loose = permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ);

            if (loose) {

                log.warn("SECURITY: the cookies file {} is readable by group/others ({}). "
                                + "Any local user can steal the sessions inside. Fix: chmod 600 {}",
                        path, permissions, path);

            }

            return loose;

        } catch (UnsupportedOperationException e) {

            // Windows/other non-POSIX filesystems: ACLs are out of scope here.
            log.debug("Cannot check POSIX permissions of {}: not supported", path);
            return false;

        } catch (IOException e) {

            log.warn("Cannot read permissions of {}: {}", path, e.getMessage());
            return false;

        }

    }

}
