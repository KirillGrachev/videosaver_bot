package eu.neydev.saver.core.api;

import org.jetbrains.annotations.NotNull;

/**
 * A platform-independent user identifier: platform + external id
 * (Telegram user id, VK user id, Discord snowflake - as strings,
 * so as not to lose precision and not drag platform types into the core).
 */
public record PlatformUser(@NotNull Platform platform, String externalId) {

    public PlatformUser {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId cannot be empty");
        }
    }

    /** Canonical key for storage, metrics and logs: {@code telegram:123456}. */
    public String key() {
        return platform.id() + ":" + externalId;
    }

    @Override
    public @NotNull String toString() {
        return key();
    }

}

