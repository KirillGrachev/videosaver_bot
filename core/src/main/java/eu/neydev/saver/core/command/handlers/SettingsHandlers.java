package eu.neydev.saver.core.command.handlers;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.command.Actions;
import eu.neydev.saver.core.command.CommandHandler;
import eu.neydev.saver.core.command.Interaction;
import eu.neydev.saver.core.command.ReplyBuilder;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.service.UserService;
import eu.neydev.saver.core.storage.JobRepository;
import eu.neydev.saver.core.storage.UsageRepository;
import eu.neydev.saver.core.storage.UserSettings;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Settings, quality presets, language picker and the personal stats screen. */
public final class SettingsHandlers {

    private final ReplyBuilder replies;
    private final UserService users;
    private final JobRepository jobs;
    private final UsageRepository usage;
    private final Clock clock;

    public SettingsHandlers(ReplyBuilder replies, UserService users,
                            JobRepository jobs, UsageRepository usage, Clock clock) {
        this.replies = replies;
        this.users = users;
        this.jobs = jobs;
        this.usage = usage;
        this.clock = clock;
    }

    public CommandHandler settings() {
        return interaction -> List.of(replies.settings(interaction));
    }

    public CommandHandler qualityMenu() {
        return interaction -> List.of(replies.qualityMenu(interaction));
    }

    public CommandHandler setQuality(String presetId) {

        return interaction -> {

            QualityPreset preset = QualityPreset.fromIdOrDefault(
                    presetId, interaction.settings().quality());

            users.setQuality(interaction.update().user(), preset);

            return List.of(replies.qualitySet(interaction, preset));

        };

    }

    public CommandHandler langMenu() {
        return interaction -> List.of(replies.langMenu(interaction, 0));
    }

    public CommandHandler langPage(int page) {
        return interaction -> List.of(replies.langMenu(interaction, page));
    }

    public CommandHandler setLang(String code) {

        return interaction -> {

            if (!users.isSupportedLanguage(code)) {
                // An unsupported code in a callback means a stale button after a
                // config change: answer in the CURRENT language, do not persist.
                return List.of(replies.langMenu(interaction, 0));
            }

            users.setLocale(interaction.update().user(), code);

            return List.of(replies.langSet(interaction, code));

        };

    }

    public CommandHandler stats() {

        return interaction -> {

            ReplyBuilder.UserStats stats = new ReplyBuilder.UserStats(
                    jobs.countSince(interaction.update().user(),
                            clock.instant().minus(Duration.ofHours(1))),
                    usage.get(interaction.update().user(),
                            LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toEpochDay()),
                    jobs.recent(interaction.update().user(), 5));

            return List.of(replies.stats(interaction, stats));

        };

    }

    public List<OutboundMessage> routeAction(Interaction interaction, String actionId) {

        if (Actions.hasPrefix(actionId, Actions.QUALITY_SET_PREFIX)) {
            return setQuality(Actions.suffix(actionId, Actions.QUALITY_SET_PREFIX))
                    .handle(interaction);
        }

        if (Actions.hasPrefix(actionId, Actions.LANG_SET_PREFIX)) {
            return setLang(Actions.suffix(actionId, Actions.LANG_SET_PREFIX))
                    .handle(interaction);
        }

        if (Actions.hasPrefix(actionId, Actions.LANG_PAGE_PREFIX)) {
            return langPage(Actions.pageOf(actionId, Actions.LANG_PAGE_PREFIX))
                    .handle(interaction);
        }

        return List.of();

    }

    /** Current settings, for callers that already hold them (the router's throttle reply). */
    public UserSettings settingsOf(Interaction interaction) {
        return interaction.settings();
    }

}
