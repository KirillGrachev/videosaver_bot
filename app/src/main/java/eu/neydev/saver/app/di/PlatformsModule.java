package eu.neydev.saver.app.di;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.i18n.MessageBundleHolder;
import eu.neydev.saver.platform.discord.DiscordAdapter;
import eu.neydev.saver.platform.slack.SlackAdapter;
import eu.neydev.saver.platform.telegram.TelegramAdapter;
import eu.neydev.saver.platform.viber.ViberAdapter;
import eu.neydev.saver.platform.vk.VkAdapter;
import eu.neydev.saver.platform.whatsapp.WhatsAppAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Platform DI module: each active platform provides one {@link PlatformAdapter} into
 * the Multibinder. Inactive ones are simply not installed - the graph stays valid, the
 * bot starts degraded. A new platform = its own module + config section.
 */
public final class PlatformsModule extends AbstractModule {

    private static final Logger log = LoggerFactory.getLogger(PlatformsModule.class);

    private final AppConfig config;
    private final MessageBundleHolder bundleHolder;

    public PlatformsModule(AppConfig config, MessageBundleHolder bundleHolder) {
        this.config = config;
        this.bundleHolder = bundleHolder;
    }

    @Override
    protected void configure() {

        Multibinder<PlatformAdapter> binder = Multibinder.newSetBinder(binder(), PlatformAdapter.class);
        String publicUrl = config.webApp().enabled() ? config.webApp().publicUrl() : null;

        // No Mini App in the saver bot: the menu-button URL stays null even when the
        // ops webapp has a public address (that one serves /healthz, not a UI).
        // platforms.telegram.id is repurposed as the LOCAL Bot API server base
        // (e.g. http://127.0.0.1:8081): with telegram-bot-api running locally the
        // 50 MB upload cap becomes 2 GB - set delivery.caps.telegram to match.
        section(Platform.TELEGRAM).ifPresent(section -> {

            org.telegram.telegrambots.meta.TelegramUrl apiBase =
                    parseTelegramApiBase(section.extraId());

            if (apiBase != null) {

                log.info("Telegram: using the local Bot API server at {}:{} "
                                + "(raise delivery.caps.telegram to use the 2 GB limit)",
                        apiBase.getHost(), apiBase.getPort());

            }

            binder.addBinding().toInstance(new TelegramAdapter(
                    section.token(), null, "Saver", apiBase, section.proxy()));

        });
        section(Platform.VK).ifPresent(section ->
                binder.addBinding().toInstance(new VkAdapter(section.token(), section.extraId())));
        section(Platform.DISCORD).ifPresent(section ->
                binder.addBinding().toInstance(new DiscordAdapter(section.token(), bundleHolder)));
        section(Platform.SLACK).ifPresent(section ->
                binder.addBinding().toInstance(new SlackAdapter(section.token(), section.extraId())));
        section(Platform.WHATSAPP).ifPresent(section ->
                binder.addBinding().toInstance(new WhatsAppAdapter(
                        section.token(), section.extraId(), section.secret())));
        section(Platform.VIBER).ifPresent(section -> {

            if (publicUrl == null) {
                log.warn("Viber media needs webapp.public-url (Viber fetches files by URL): "
                        + "without it the bot answers Viber with text and links only");
            }

            binder.addBinding().toInstance(new ViberAdapter(
                    section.token(), publicUrl, section.secret()));

        });

    }

    /**
     * A platform is active when enabled, has a token and meets the platform
     * requirements (id, webhook infrastructure, secrets). Otherwise - degradation with
     * a warning, not a bot crash.
     */
    private Optional<AppConfig.PlatformSection> section(Platform platform) {

        AppConfig.PlatformSection section = config.platform(platform);

        if (!section.isActive()) {
            return Optional.empty();
        }

        boolean webhookReady = config.webApp().enabled() && config.webApp().publicUrl() != null;
        boolean extraOk = switch (platform) {

            case VK, SLACK -> section.extraId() != null && !section.extraId().isBlank();
            case WHATSAPP -> webhookReady && section.extraId() != null
                    && !section.extraId().isBlank()
                    && (hasSecret(section) || section.extraId().split(":").length > 2);
            case VIBER -> webhookReady && hasSecret(section);
            default -> true;

        };

        if (!extraOk) {
            log.warn("Platform {} is enabled but requirements are not met (id/webhook/secret) - "
                    + "skipped", platform.id());
        }

        return extraOk ? Optional.of(section) : Optional.empty();

    }

    private static boolean hasSecret(AppConfig.PlatformSection section) {
        return section.secret() != null && !section.secret().isBlank();
    }

    /**
     * Parses {@code http(s)://host[:port]} into the library's TelegramUrl, or null for
     * the default api.telegram.org. A malformed value fails the platform (and the log
     * says why), not the whole bot.
     */
    private static org.telegram.telegrambots.meta.TelegramUrl parseTelegramApiBase(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        try {

            java.net.URI uri = java.net.URI.create(value.trim());
            String schema = uri.getScheme() == null ? "https" : uri.getScheme();
            int port = uri.getPort() > 0 ? uri.getPort()
                    : "http".equals(schema) ? 80 : 443;

            return org.telegram.telegrambots.meta.TelegramUrl.builder()
                    .schema(schema)
                    .host(uri.getHost())
                    .port(port)
                    .build();

        } catch (RuntimeException e) {

            log.error("platforms.telegram.id is not a valid Bot API base URL ('{}'): "
                    + "using api.telegram.org", value);
            return null;

        }

    }

}
