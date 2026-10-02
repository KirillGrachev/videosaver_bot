package eu.neydev.saver.core.command;

import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.command.CommandCatalog.Spec;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.i18n.MessageBundleHolder;
import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceCatalog;
import eu.neydev.saver.core.source.SourceCatalogHolder;
import eu.neydev.saver.core.storage.JobRepository;
import eu.neydev.saver.core.storage.UsageRepository;
import eu.neydev.saver.core.storage.UserSettings;
import eu.neydev.saver.core.text.RichText;
import eu.neydev.saver.core.util.ByteFormat;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The DIALOG message factory: menus, settings, sources browser, stats, admin.
 * The download lifecycle has its own factory ({@link JobReplies}) - keeping the two
 * apart is what stops this class from becoming a god object.
 *
 * <p>Everything a user reads is assembled here from i18n templates ({@code message.*})
 * and {@link MenuFactory} keyboards. Handlers stay logic-only, and a wording change is
 * a YAML edit in every language at once.
 */
public final class ReplyBuilder {

    /** Search results per page - a search that found 40 sites still reads on a phone. */
    private static final int SEARCH_PAGE_SIZE = 5;

    private final MessageBundleHolder holder;
    private final MenuFactory menus;
    private final AppConfig config;
    private final SourceCatalogHolder catalogHolder;
    private final Clock clock;

    /** Stats snapshot for /stats, gathered by the handler from the repositories. */
    public record UserStats(int jobsThisHour, UsageRepository.DayUsage today,
                            List<JobRepository.JobRecord> recent) {
    }

    /** Ops snapshot for /admin. */
    public record AdminStats(String tools, int jobsActive, int jobsQueued,
                             long vaultBytes, long vaultMax, long users,
                             Map<String, Long> statusCounts,
                             List<Map.Entry<String, Long>> topSources) {
    }

    public ReplyBuilder(MessageBundleHolder holder, MenuFactory menus, AppConfig config,
                        SourceCatalogHolder catalogHolder, Clock clock) {
        this.holder = holder;
        this.menus = menus;
        this.config = config;
        this.catalogHolder = catalogHolder;
        this.clock = clock;
    }

    // ---- helpers --------------------------------------------------------------------

    public Locale localeOf(UserSettings settings) {
        return Locale.forLanguageTag(settings.locale());
    }

    private RichText text(String key, Locale locale, Map<String, Object> params) {
        return holder.renderer().rich(key, locale, params);
    }

    private RichText text(String key, Locale locale) {
        return holder.renderer().rich(key, locale);
    }

    /** Callbacks EDIT the pressed message (no menu spam), typed commands SEND a new one. */
    private OutboundMessage reply(Interaction interaction, RichText text, InlineKeyboard keyboard) {

        String editTarget = interaction.editMessageId();

        if (editTarget != null) {

            return new OutboundMessage.Edit(interaction.update().user().platform(),
                    interaction.chatId(), editTarget, text, keyboard);

        }

        return new OutboundMessage.Send(interaction.update().user().platform(),
                interaction.chatId(), text, keyboard);

    }

    // ---- menus ------------------------------------------------------------------------

    public OutboundMessage start(Interaction interaction) {

        Locale locale = interaction.locale();

        return reply(interaction, text("message.start", locale), menus.mainMenu(locale));

    }

    public OutboundMessage help(Interaction interaction) {

        Locale locale = interaction.locale();
        StringBuilder sb = new StringBuilder();

        for (Spec spec : CommandCatalog.all()) {

            // Owner-only commands stay invisible to everybody else.
            if (isOwnerOnly(spec) && !isOwner(interaction.update().user())) {
                continue;
            }

            sb.append(holder.renderer().raw("message.help.command", locale,
                            Map.of("command", "/" + spec.name(),
                                    "description", holder.renderer().raw(
                                            spec.descriptionKey(), locale))))
                    .append('\n');

        }

        sb.append('\n').append(holder.renderer().raw("message.help.tip", locale));

        return reply(interaction, RichText.parse(sb.toString()), menus.mainMenu(locale));

    }

    public OutboundMessage about(Interaction interaction) {

        Locale locale = interaction.locale();

        return reply(interaction,
                text("message.about", locale, Map.of(
                        "sources", catalogHolder.catalog().size() - 1,
                        "version", version())),
                menus.mainMenu(locale));

    }

    public OutboundMessage settings(Interaction interaction) {

        Locale locale = interaction.locale();
        UserSettings settings = interaction.settings();

        return reply(interaction, text("message.settings", locale, Map.of(
                        "quality", holder.renderer().raw(
                                "button.quality." + settings.quality().id(), locale),
                        "language", config.locale().displayNames()
                                .getOrDefault(settings.locale(), settings.locale()))),
                menus.settingsMenu(locale));

    }

    public OutboundMessage qualityMenu(Interaction interaction) {

        Locale locale = interaction.locale();

        return reply(interaction,
                text("message.quality.title", locale),
                menus.qualityMenu(locale, interaction.settings().quality()));

    }

    public OutboundMessage qualitySet(Interaction interaction,
                                      eu.neydev.saver.core.extract.QualityPreset preset) {

        Locale locale = interaction.locale();

        return reply(interaction,
                text("message.quality.set", locale, Map.of(
                        "quality", holder.renderer().raw("button.quality." + preset.id(), locale))),
                menus.settingsMenu(locale));

    }

    public OutboundMessage langMenu(Interaction interaction, int page) {

        Locale locale = interaction.locale();

        return reply(interaction,
                text("message.lang.choose", locale),
                menus.langPage(locale, page));

    }

    public OutboundMessage langSet(Interaction interaction, String code) {

        // Confirmation is rendered in the NEW language: the user sees immediately
        // that the switch worked.
        Locale locale = Locale.forLanguageTag(code);

        return reply(interaction,
                text("message.lang.set", locale),
                menus.settingsMenu(locale));

    }

    public OutboundMessage sourcesPage(Interaction interaction, int page) {

        Locale locale = interaction.locale();
        SourceCatalog catalog = catalogHolder.catalog();
        List<Source> sources = new ArrayList<>(catalog.all());
        int pageSize = 8;
        int pages = Math.max(1, (sources.size() + pageSize - 1) / pageSize);
        int safePage = Math.min(Math.max(0, page), pages - 1);

        StringBuilder sb = new StringBuilder(holder.renderer()
                .raw("message.sources.title", locale, Map.of("count", catalog.size() - 1)))
                .append('\n').append('\n');

        for (int i = safePage * pageSize;
             i < Math.min((safePage + 1) * pageSize, sources.size()); i++) {

            appendSourceLine(sb, sources.get(i));

        }

        sb.append('\n').append(holder.renderer().raw("message.sources.legend", locale));

        return reply(interaction, RichText.parse(sb.toString()),
                menus.sourcesNav(locale, safePage, pages));

    }

    /** One catalog line: status mark, title, and the caveat note when the source has one. */
    private void appendSourceLine(StringBuilder sb, Source source) {

        sb.append(statusMark(source))
                .append(' ')
                .append(RichText.escapeValue(source.title()));

        if (source.note() != null && !source.note().isBlank()) {
            sb.append(" (").append(RichText.escapeValue(source.note())).append(')');
        }

        sb.append('\n');

    }

    public OutboundMessage sourcesSearchPrompt(Interaction interaction) {

        Locale locale = interaction.locale();

        return reply(interaction, text("message.sources.search.prompt", locale),
                menus.sourcesNav(locale, 0, 1));

    }

    /** Paginated search results; the query lives in the handler's session, the page here. */
    public OutboundMessage sourcesSearchResult(Interaction interaction, String query, int page) {

        Locale locale = interaction.locale();
        List<Source> found = catalogHolder.catalog().search(query);

        if (found.isEmpty()) {

            return reply(interaction,
                    text("message.sources.search.empty", locale, Map.of("query", query)),
                    menus.sourcesNav(locale, 0, 1));

        }

        int pages = Math.max(1, (found.size() + SEARCH_PAGE_SIZE - 1) / SEARCH_PAGE_SIZE);
        int safePage = Math.min(Math.max(0, page), pages - 1);

        StringBuilder sb = new StringBuilder();

        for (int i = safePage * SEARCH_PAGE_SIZE;
             i < Math.min((safePage + 1) * SEARCH_PAGE_SIZE, found.size()); i++) {

            appendSourceLine(sb, found.get(i));

        }

        sb.append('\n').append(holder.renderer().raw("message.sources.search.found", locale,
                Map.of("count", found.size())));

        return reply(interaction, RichText.parse(sb.toString()),
                menus.searchNav(locale, safePage, pages));

    }

    public OutboundMessage stats(Interaction interaction, UserStats stats) {

        Locale locale = interaction.locale();
        StringBuilder sb = new StringBuilder();

        sb.append(holder.renderer().raw("message.stats", locale, Map.of(
                        "jobs_hour", stats.jobsThisHour(),
                        "jobs_today", stats.today().jobs(),
                        "bytes_today", ByteFormat.human(stats.today().bytes()))))
                .append('\n');

        if (!stats.recent().isEmpty()) {

            sb.append('\n').append(holder.renderer().raw("message.stats.recent", locale))
                    .append('\n');

            for (JobRepository.JobRecord job : stats.recent()) {

                sb.append(statusMark(job.status())).append(' ')
                        .append(RichText.escapeValue(shorten(job.url(), 48)))
                        .append(" · ")
                        .append(job.sourceId() == null ? "?" : job.sourceId())
                        .append('\n');

            }

        }

        return reply(interaction, RichText.parse(sb.toString()), menus.settingsMenu(locale));

    }

    public OutboundMessage admin(Interaction interaction, AdminStats stats) {

        Locale locale = interaction.locale();
        StringBuilder sb = new StringBuilder();

        sb.append(holder.renderer().raw("message.admin", locale, Map.of(
                        "tools", stats.tools(),
                        "active", stats.jobsActive(),
                        "queued", stats.jobsQueued(),
                        "vault", ByteFormat.human(stats.vaultBytes()) + " / "
                                + ByteFormat.human(stats.vaultMax()),
                        "users", stats.users())))
                .append('\n');

        if (!stats.statusCounts().isEmpty()) {

            sb.append('\n');
            stats.statusCounts().forEach((status, count) ->
                    sb.append('`').append(status).append("` ").append(count).append('\n'));

        }

        if (!stats.topSources().isEmpty()) {

            sb.append('\n');

            for (Map.Entry<String, Long> entry : stats.topSources()) {
                sb.append('`').append(entry.getKey()).append("` ").append(entry.getValue()).append('\n');
            }

        }

        return new OutboundMessage.Send(interaction.update().user().platform(),
                interaction.chatId(),
                RichText.parse(sb.toString()), InlineKeyboard.of(List.of(
                InlineKeyboard.KeyboardButton.callback(
                        holder.renderer().raw("button.reload", locale), Actions.RELOAD))));

    }

    public OutboundMessage reloadDone(Interaction interaction, int sources) {

        return reply(interaction,
                text("message.reload.done", interaction.locale(), Map.of("sources", sources)),
                InlineKeyboard.empty());

    }

    public OutboundMessage notOwner(Interaction interaction) {

        return reply(interaction, text("message.error.not_owner", interaction.locale()),
                InlineKeyboard.empty());

    }

    public boolean isOwner(PlatformUser user) {
        return config.ownerKeys().contains(user.key());
    }

    private boolean isOwnerOnly(Spec spec) {
        return spec == CommandCatalog.ADMIN || spec == CommandCatalog.RELOAD;
    }

    // ---- conversation and hints ---------------------------------------------------------

    public OutboundMessage unknownCommand(Interaction interaction) {

        return reply(interaction, text("message.error.unknown_command", interaction.locale()),
                InlineKeyboard.empty());

    }

    public OutboundMessage hintNoUrl(Interaction interaction) {

        return reply(interaction, text("message.hint.no_url", interaction.locale()),
                menus.mainMenu(interaction.locale()));

    }

    public OutboundMessage urlPrompt(Interaction interaction) {

        return reply(interaction, text("message.prompt.url", interaction.locale()),
                InlineKeyboard.empty());

    }

    public OutboundMessage cancelNone(Interaction interaction) {

        return reply(interaction, text("message.cancel.none", interaction.locale()),
                InlineKeyboard.empty());

    }

    public OutboundMessage cancelDone(Interaction interaction, int cancelled) {

        return reply(interaction,
                text("message.cancel.done", interaction.locale(), Map.of("count", cancelled)),
                InlineKeyboard.empty());

    }

    // ---- formatting helpers -------------------------------------------------------------

    private String statusMark(Source source) {

        return switch (source.status()) {
            case OK -> "✓";
            case LIMITED -> "⚠";
            case LOGIN -> "\uD83D\uDD12";
        };

    }

    private String statusMark(String status) {

        return switch (status == null ? "" : status) {
            case "SUCCEEDED" -> "✓";
            case "FAILED", "INTERRUPTED" -> "✗";
            case "CANCELLED" -> "⊘";
            default -> "…";
        };

    }

    private static String shorten(String value, int max) {

        String oneLine = value.replaceAll("\\s+", " ").trim();

        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "\u2026";

    }

    private String version() {

        String version = getClass().getPackage().getImplementationVersion();
        return version != null ? version : "dev";

    }

    /** Today's epoch day in UTC - the quota day boundary. */
    public long epochDay() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toEpochDay();
    }

    public AppConfig config() {
        return config;
    }

    public SourceCatalogHolder catalogHolder() {
        return catalogHolder;
    }

    public MessageBundleHolder holder() {
        return holder;
    }

}
