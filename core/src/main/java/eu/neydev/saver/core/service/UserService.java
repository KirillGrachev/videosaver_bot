package eu.neydev.saver.core.service;

import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.i18n.LocaleResolver;
import eu.neydev.saver.core.storage.UserRepository;
import eu.neydev.saver.core.storage.UserSettings;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;

/**
 * User settings lifecycle: every inbound event resolves into a {@link UserSettings}
 * row (created on first contact), and explicit user choices persist through here.
 * Thin by design - the storage upsert is idempotent, so parallel first-contact races
 * from the worker pool are safe without locking in this layer.
 */
public final class UserService {

    private final UserRepository users;
    private final LocaleResolver localeResolver;
    private final AppConfig config;
    private final Clock clock;

    public UserService(UserRepository users, LocaleResolver localeResolver,
                       AppConfig config, Clock clock) {
        this.users = users;
        this.localeResolver = localeResolver;
        this.config = config;
        this.clock = clock;
    }

    public UserSettings getOrCreate(PlatformUser user, String chatId,
                                    @Nullable String localeHint) {

        return users.getOrCreate(user, chatId,
                localeResolver.resolve(localeHint).getLanguage(),
                config.downloader().defaultQuality());

    }

    public UserSettings setLocale(PlatformUser user, String language) {

        users.updateLocale(user, language);

        return users.find(user).orElseThrow(() -> new IllegalStateException(
                "User row vanished after locale update: " + user.key()));

    }

    public UserSettings setQuality(PlatformUser user, QualityPreset quality) {

        users.updateQuality(user, quality);

        return users.find(user).orElseThrow(() -> new IllegalStateException(
                "User row vanished after quality update: " + user.key()));

    }

    public boolean isSupportedLanguage(String language) {
        return localeResolver.isSupported(language);
    }

    public long count() {
        return users.count();
    }

    public Clock clock() {
        return clock;
    }

}
