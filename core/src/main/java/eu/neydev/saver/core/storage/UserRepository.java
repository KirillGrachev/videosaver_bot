package eu.neydev.saver.core.storage;

import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.extract.QualityPreset;

import java.time.Instant;
import java.util.Optional;

/**
 * User settings persistence. Implementations MUST upsert idempotently: two inbound
 * updates from the same user in parallel worker threads are a normal race, and the
 * loser must not crash the router.
 */
public interface UserRepository {

    /** Loads or creates the settings row; {@code localeHint} seeds a new row only. */
    UserSettings getOrCreate(PlatformUser user, String chatId, String localeHint,
                             QualityPreset defaultQuality);

    Optional<UserSettings> find(PlatformUser user);

    void updateLocale(PlatformUser user, String locale);

    void updateQuality(PlatformUser user, QualityPreset quality);

    /** Remembers the dialog the user last spoke from (menus move between chats). */
    void touchChat(PlatformUser user, String chatId);

    long count();

    Instant now();

}
