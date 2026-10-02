package eu.neydev.saver.core.command;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.command.handlers.AdminHandlers;
import eu.neydev.saver.core.command.handlers.DownloadHandlers;
import eu.neydev.saver.core.command.handlers.MenuHandlers;
import eu.neydev.saver.core.command.handlers.SettingsHandlers;
import eu.neydev.saver.core.command.handlers.SourcesHandlers;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.conversation.ConversationStore;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.pipeline.OutboundDispatcher;
import eu.neydev.saver.core.pipeline.UserThrottle;
import eu.neydev.saver.core.security.UrlIntake;
import eu.neydev.saver.core.service.UserService;
import eu.neydev.saver.core.storage.UserSettings;
import eu.neydev.saver.core.util.TraceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A thin router: tracing, anti-flood, handler lookup in the tables of commands/actions
 * (names - from {@link CommandCatalog}) and handing replies to the outbound dispatcher.
 * Business logic lives in the handler classes.
 *
 * <p>Free text is special in a downloader bot: unlike a birthday bot, where unknown
 * text means "show help", here a pasted link IS the product. Text without a link and
 * without a dialog state gets the gentle hint, not a help wall.
 */
public final class CommandRouter implements Consumer<IncomingUpdate> {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);
    private static final long THROTTLE_WINDOW_MILLIS = 10_000;

    private final UserService userService;
    private final ConversationStore conversations;
    private final ReplyBuilder replies;
    private final JobReplies jobReplies;
    private final DownloadHandlers downloadHandlers;
    private final SettingsHandlers settingsHandlers;
    private final SourcesHandlers sourcesHandlers;
    private final OutboundDispatcher dispatcher;
    private final MetricsRegistry metrics;
    private final UrlIntake intake;
    private final UserThrottle throttle;

    private final Map<String, CommandHandler> commands = new HashMap<>();
    private final Map<String, CommandHandler> actions = new HashMap<>();

    public CommandRouter(UserService userService,
                         ConversationStore conversations,
                         ReplyBuilder replies,
                         JobReplies jobReplies,
                         MenuHandlers menuHandlers,
                         DownloadHandlers downloadHandlers,
                         SettingsHandlers settingsHandlers,
                         SourcesHandlers sourcesHandlers,
                         AdminHandlers adminHandlers,
                         OutboundDispatcher dispatcher,
                         MetricsRegistry metrics,
                         UrlIntake intake,
                         AppConfig config) {

        this.userService = userService;
        this.conversations = conversations;
        this.replies = replies;
        this.jobReplies = jobReplies;
        this.downloadHandlers = downloadHandlers;
        this.settingsHandlers = settingsHandlers;
        this.sourcesHandlers = sourcesHandlers;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.intake = intake;
        this.throttle = new UserThrottle(
                config.pipeline().inboundPerUserPerWindow(), THROTTLE_WINDOW_MILLIS);

        wireCommands(menuHandlers, downloadHandlers, settingsHandlers, sourcesHandlers,
                adminHandlers, config);
        wireActions(menuHandlers, downloadHandlers, settingsHandlers, sourcesHandlers,
                adminHandlers, config);

    }

    /** The command table is built from the catalog: names and aliases in one place. */
    private void wireCommands(MenuHandlers menu, DownloadHandlers download,
                              SettingsHandlers settings, SourcesHandlers sources,
                              AdminHandlers admin, AppConfig config) {

        bind(CommandCatalog.START, menu.start());
        bind(CommandCatalog.HELP, menu.help());
        bind(CommandCatalog.SAVE, download.save());
        bind(CommandCatalog.STOP, download.stop());
        bind(CommandCatalog.QUALITY, settings.qualityMenu());
        bind(CommandCatalog.SETTINGS, settings.settings());
        bind(CommandCatalog.SOURCES, sources.sources());
        bind(CommandCatalog.STATS, settings.stats());
        bind(CommandCatalog.LANG, settings.langMenu());
        bind(CommandCatalog.ABOUT, menu.about());
        bind(CommandCatalog.ADMIN, ownerOnly(admin.admin(), config));
        bind(CommandCatalog.RELOAD, ownerOnly(admin.reload(), config));

    }

    private void wireActions(MenuHandlers menu, DownloadHandlers download,
                             SettingsHandlers settings, SourcesHandlers sources,
                             AdminHandlers admin, AppConfig config) {

        actions.put(Actions.BACK, menu.back());
        actions.put(Actions.SETTINGS, settings.settings());
        actions.put(Actions.QUALITY_MENU, settings.qualityMenu());
        actions.put(Actions.LANG_MENU, settings.langMenu());
        actions.put(Actions.SOURCES, sources.sources());
        actions.put(Actions.SOURCES_SEARCH, sources.searchPrompt());
        actions.put(Actions.STATS, settings.stats());
        actions.put(Actions.ABOUT, menu.about());
        actions.put(Actions.HELP, menu.help());
        actions.put(Actions.JOB_STOP, download.stop());
        actions.put(Actions.ADMIN, ownerOnly(admin.admin(), config));
        actions.put(Actions.RELOAD, ownerOnly(admin.reload(), config));
        actions.put(Actions.NOOP, interaction -> List.of());

    }

    /** Owner gate: everyone else gets a polite refusal and a metric. */
    private CommandHandler ownerOnly(CommandHandler handler, AppConfig config) {

        return interaction -> {

            if (!config.ownerKeys().contains(interaction.update().user().key())) {

                metrics.increment("admin_denied_total",
                        "platform", interaction.update().user().platform().id());

                return List.of(replies.notOwner(interaction));

            }

            return handler.handle(interaction);

        };

    }

    private void bind(CommandCatalog.Spec spec, CommandHandler handler) {
        commands.put("/" + spec.name(), handler);
        spec.aliases().forEach(alias -> commands.put("/" + alias, handler));
    }

    @Override
    public void accept(IncomingUpdate update) {

        TraceId.put(update.traceId());

        try {

            UserSettings settings = userService.getOrCreate(
                    update.user(), update.chatId(), localeHint(update));

            if (throttled(update, settings)) {
                return;
            }

            for (OutboundMessage reply : route(update, settings)) {
                dispatcher.submit(reply);
            }

        } finally {
            TraceId.clear();
        }

    }

    /**
     * Anti-flood: protects the core from command spam. The user gets ONE "too fast"
     * reply per burst (THROTTLED_REPLY), then silence - so anti-flood itself does not
     * become a flood.
     */
    private boolean throttled(IncomingUpdate update, UserSettings settings) {

        UserThrottle.Verdict verdict = throttle.tryAcquire(update.user());

        if (verdict == UserThrottle.Verdict.PASS) {
            return false;
        }

        metrics.increment("inbound_throttled_total", "platform", update.user().platform().id());

        if (verdict == UserThrottle.Verdict.THROTTLED_REPLY) {

            dispatcher.submit(jobReplies.flood(update.user().platform(), update.chatId(),
                    replies.localeOf(settings)));

        }

        return true;

    }

    private List<OutboundMessage> route(IncomingUpdate update, UserSettings settings) {

        return switch (update) {

            case IncomingUpdate.TextMessage message ->
                    routeText(new Interaction(message, settings, ""), message);

            case IncomingUpdate.Callback callback ->
                    routeCallback(new Interaction(callback, settings), callback);

            case IncomingUpdate.FormSubmit form ->
                    routeForm(new Interaction(form, settings), form);

        };

    }

    private List<OutboundMessage> routeText(Interaction interaction,
                                            IncomingUpdate.TextMessage message) {

        metrics.increment("inbound_text_total",
                "platform", interaction.update().user().platform().id());

        String text = message.text().trim();

        if (text.startsWith("/")) {

            // Split the RAW text: the argument must keep its case and any @mention of
            // the command itself is stripped from the name only.
            String[] parts = text.split("\\s+", 2);
            String rawName = parts[0].toLowerCase(Locale.ROOT);
            int at = rawName.indexOf('@');
            String command = at > 0 ? rawName.substring(0, at) : rawName;
            String args = parts.length > 1 ? parts[1].trim() : "";

            metrics.increment("commands_total", "command", command);

            Interaction withArgs = new Interaction(message, interaction.settings(), args);

            return CommandCatalog.resolve(command.substring(1))
                    .map(spec -> commands.getOrDefault("/" + spec.name(),
                            menuFallback()).handle(withArgs))
                    .orElseGet(() -> List.of(replies.unknownCommand(interaction)));

        }

        // A dialog state owns the text first: it is the answer to a prompt.
        ConversationStore.State state = conversations.current(
                interaction.update().user()).orElse(null);

        if (state == ConversationStore.State.AWAITING_URL) {
            return downloadHandlers.handleAwaitingUrl(interaction, text);
        }

        if (state == ConversationStore.State.AWAITING_SOURCE_SEARCH) {
            return sourcesHandlers.handleSearchText(interaction, text);
        }

        if (intake.containsUrl(text)) {
            return downloadHandlers.handleFreeText(interaction, text);
        }

        return List.of(replies.hintNoUrl(interaction));

    }

    private List<OutboundMessage> routeCallback(Interaction interaction,
                                                IncomingUpdate.Callback callback) {

        metrics.increment("callbacks_total", "action", callback.actionId(),
                "platform", interaction.update().user().platform().id());

        List<OutboundMessage> out = new ArrayList<>(2);
        out.add(new OutboundMessage.AnswerCallback(interaction.update().user().platform(),
                callback.chatId(), callback.interactionId(), "", false));

        String actionId = callback.actionId();

        // Prefixed actions first: q:*, lg:*, lp:*, sp:*
        List<OutboundMessage> prefixed = settingsHandlers.routeAction(interaction, actionId);

        if (prefixed.isEmpty()) {
            prefixed = sourcesHandlers.routeAction(interaction, actionId);
        }

        if (!prefixed.isEmpty()) {
            out.addAll(prefixed);
            return out;
        }

        CommandHandler handler = actions.get(actionId);

        if (handler != null) {
            out.addAll(handler.handle(interaction));
        } else {
            log.debug("Unknown action '{}' from {}", actionId,
                    interaction.update().user().key());
        }

        return out;

    }

    private List<OutboundMessage> routeForm(Interaction interaction,
                                            IncomingUpdate.FormSubmit form) {

        // The slash form (Discord modal) carries the same URL a text command would.
        if (CommandCatalog.SAVE.name().equals(form.actionId())) {

            String url = form.fields().getOrDefault("url", "");
            Interaction withArgs = new Interaction(form, interaction.settings(), url);

            return commands.get("/" + CommandCatalog.SAVE.name()).handle(withArgs);

        }

        return List.of();

    }

    private CommandHandler menuFallback() {
        return interaction -> List.of(replies.start(interaction));
    }

    private static String localeHint(IncomingUpdate update) {
        return switch (update) {
            case IncomingUpdate.TextMessage message -> message.localeHint();
            case IncomingUpdate.Callback callback -> callback.localeHint();
            case IncomingUpdate.FormSubmit form -> form.localeHint();
        };
    }

}
