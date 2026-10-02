package eu.neydev.saver.core.storage;

import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.extract.QualityPreset;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * What the bot remembers about a user: where to answer, which language, which quality
 * preset. Deliberately small - a downloader bot stores no private profiles, only the
 * settings needed to serve the next request the same way.
 */
public record UserSettings(@NotNull PlatformUser user,
                           @NotNull String chatId,
                           @NotNull String locale,
                           @NotNull QualityPreset quality,
                           @NotNull Instant createdAt,
                           @NotNull Instant updatedAt) {

    public UserSettings withLocale(String newLocale, Instant now) {
        return new UserSettings(user, chatId, newLocale, quality, createdAt, now);
    }

    public UserSettings withQuality(QualityPreset newQuality, Instant now) {
        return new UserSettings(user, chatId, locale, newQuality, createdAt, now);
    }

    public UserSettings withChatId(String newChatId, Instant now) {
        return new UserSettings(user, newChatId, locale, quality, createdAt, now);
    }

}
