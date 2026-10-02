package eu.neydev.saver.core.api;

import org.jetbrains.annotations.Nullable;

/**
 * The adapter's optional contract for asynchronous connection: reports a fatal
 * a start error (invalid token, missing rights, bot deleted) if the background
 * connection already managed to receive it. Network errors do not end up here -
 * they are retried in the background, and the bootstrap level continues
 * without the platform.
 *
 * <p>A reported error marks the platform as failed at startup and is logged with
 * the fix. The bot exits only when every configured platform failed: with several
 * platforms one broken token must not take the rest down.
 */
public interface StartupFailureProbe {

    /** A fatal connection error or {@code null} if there is none (yet). */
    @Nullable PlatformException fatalStartupError();

}

