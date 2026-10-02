package eu.neydev.saver.platform.discord;

import eu.neydev.saver.core.api.IncomingUpdate;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.PlatformUser;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.UpdateSink;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import eu.neydev.saver.core.api.SendResult;

/**
 * Discord adapter (JDA 6). Privacy by default: the whole saver dialog
 * goes into the user's DM (chatId = discord:&lt;userId&gt;), slash commands and buttons
 * in guilds get only an ephemeral ack reply.
 *
 * <p>Interactions require a reply within 3 seconds, so the ack (defer/reply)
 * performed IMMEDIATELY in the mapping, and the business reply comes as a separate DM
 * via the outbound dispatcher. Follow-up hooks (toasts) live in memory for up to 14 minutes.
 */
public final class DiscordAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordAdapter.class);

    private final String token;
    private final eu.neydev.saver.core.i18n.MessageBundleHolder bundleHolder;
    private JDA jda;
    private final Map<String, HookEntry> hooks = new ConcurrentHashMap<>();

    private record HookEntry(InteractionHook hook, Instant createdAt) {
    }

    public DiscordAdapter(String token, eu.neydev.saver.core.i18n.MessageBundleHolder bundleHolder) {
        this.token = token;
        this.bundleHolder = bundleHolder;
    }

    @Override
    public Platform platform() {
        return Platform.DISCORD;
    }

    @Override
    public boolean isEnabled() {
        return token != null && !token.isBlank();
    }

    @Override
    public void start(PlatformContext context) {

        try {

            jda = JDABuilder.createLight(token, GatewayIntent.DIRECT_MESSAGES)
                    .addEventListeners(new Listener(context.instrumentedSink(Platform.DISCORD)))
                    .build()
                    .awaitReady();
            registerCommands();
            Thread.ofVirtual().name("discord-hook-purge").start(this::purgeLoop);

            log.info("Discord: gateway connected, commands registered");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Discord: start interrupted", e);
        } catch (Exception e) {
            throw new PlatformException("Discord: failed to start: " + e.getMessage(), e);
        }

    }

    /**
     * Slash commands are registered from {@link eu.neydev.saver.core.command.CommandCatalog} -
     * a single source of truth for names/options; descriptions are localized from the i18n bundle.
     */
    private void registerCommands() {

        List<net.dv8tion.jda.api.interactions.commands.build.CommandData> commands = new ArrayList<>();

        for (eu.neydev.saver.core.command.CommandCatalog.Spec spec
                : eu.neydev.saver.core.command.CommandCatalog.all()) {

            String description = bundleHolder.renderer()
                    .raw(spec.descriptionKey(), java.util.Locale.ENGLISH, Map.of());
            var command = Commands.slash(spec.name(), truncate(description, 100));

            for (String option : spec.options()) {
                command.addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING,
                        option, option, false);
            }

            Map<net.dv8tion.jda.api.interactions.DiscordLocale, String> localizations = new java.util.HashMap<>();

            for (String language : bundleHolder.bundle().languages()) {
                localizations.put(net.dv8tion.jda.api.interactions.DiscordLocale.from(language),
                        truncate(bundleHolder.renderer().raw(spec.descriptionKey(),
                                java.util.Locale.forLanguageTag(language), Map.of()), 100));
            }

            command.setDescriptionLocalizations(localizations);
            commands.add(command);

        }

        jda.updateCommands().addCommands(commands).queue();

    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @Override
    public void stop() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    @Override
    public SendResult execute(OutboundMessage message) {

        try {

            return switch (message) {

                case OutboundMessage.Send send -> {
                    PrivateChannel channel = openDm(send.chatId());
                    yield SendResult.of(channel
                            .sendMessage(DiscordMarkdownRenderer.render(send.text()))
                            .setComponents(components(send.keyboard()))
                            .complete()
                            .getId());
                }

                case OutboundMessage.SendMediaGroup ignored -> throw new IllegalStateException(
                        "Discord: SendMediaGroup must be expanded by the outbound dispatcher");

                case OutboundMessage.SendMedia media -> {
                    PrivateChannel channel = openDm(media.chatId());

                    java.io.File file = media.media().file().toFile();

                    if (!file.isFile()) {
                        throw new eu.neydev.saver.core.api.PlatformException
                                .PermanentDeliveryException(
                                "Discord: media file vanished before the send: " + media.media().file());
                    }

                    // One file per message: Discord renders a single attachment as a
                    // proper player/preview, and the 25 MB default cap is per message.
                    yield SendResult.of(channel
                            .sendFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(
                                    file, media.media().fileName()))
                            .setContent(cut(DiscordMarkdownRenderer.render(media.caption()), 1900))
                            .setComponents(components(media.keyboard()))
                            .complete()
                            .getId());
                }

                case OutboundMessage.Edit edit -> {
                    PrivateChannel channel = openDm(edit.chatId());
                    channel.editMessageById(edit.messageId(),
                                    DiscordMarkdownRenderer.render(edit.text()))
                            .setComponents(components(edit.keyboard()))
                            .complete();
                    yield SendResult.of(edit.messageId());
                }

                case OutboundMessage.Delete delete -> {
                    openDm(delete.chatId()).deleteMessageById(delete.messageId()).complete();
                    yield SendResult.ack();
                }

                case OutboundMessage.AnswerCallback answer -> {
                    HookEntry entry = hooks.get(answer.interactionId());
                    if (entry != null && !answer.text().isEmpty()) {
                        entry.hook().sendMessage(answer.text()).setEphemeral(true).queue();
                    }
                    yield SendResult.ack();
                }

            };

        } catch (ErrorResponseException e) {
            throw classify(e);
        }

    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private PrivateChannel openDm(String chatId) {
        return jda.openPrivateChannelById(chatId).complete();
    }

    static List<ActionRow> components(InlineKeyboard keyboard) {

        List<ActionRow> rows = new ArrayList<>();

        for (List<InlineKeyboard.KeyboardButton> row : keyboard.rows()) {
            List<Button> buttons = new ArrayList<>();

            for (InlineKeyboard.KeyboardButton button : row) {
                if (button.url() != null) {
                    buttons.add(Button.link(button.url(), button.label()));
                } else {
                    buttons.add(switch (button.style()) {
                        case PRIMARY -> Button.primary(button.actionId(), button.label());
                        case DANGER -> Button.danger(button.actionId(), button.label());
                        case SECONDARY -> Button.secondary(button.actionId(), button.label());
                    });
                }
            }

            rows.add(ActionRow.of(buttons));

        }

        return rows;

    }

    private RuntimeException classify(ErrorResponseException e) {

        int code = e.getErrorResponse().getCode();

        // 50007 cannot send to this user, 40003 missing access, 10001 unknown channel.
        if (code == 50007 || code == 40003 || code == 10001) {
            return new PlatformException.PermanentDeliveryException("Discord: " + code, e);
        }

        // 40005 invalid form body - in practice almost always "file exceeds the size
        // limit" (boost tier changed, cap guess off). Retrying the same bytes can
        // never succeed: permanent, so the dispatcher does not burn three attempts.
        if (code == 40005) {
            return new PlatformException.PermanentDeliveryException(
                    "Discord 40005 (payload rejected, file too large?): "
                            + e.getMeaning(), e);
        }

        if (code == 429) {
            return new PlatformException.RateLimitedException("Discord 429", 1_000);
        }

        // HTTP 413 surfaces without a Discord error code: same story, permanent.
        if (e.getResponse() != null && e.getResponse().code == 413) {
            return new PlatformException.PermanentDeliveryException(
                    "Discord 413: request entity too large", e);
        }

        return new PlatformException("Discord: " + e.getMessage(), e);

    }

    private void purgeLoop() {

        while (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {

            hooks.entrySet().removeIf(entry ->
                    entry.getValue().createdAt().isBefore(Instant.now().minusSeconds(14 * 60)));

            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

        }

    }

    private final class Listener extends ListenerAdapter {

        private final UpdateSink sink;

        private Listener(UpdateSink sink) {
            this.sink = sink;
        }

        @Override
        public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {

            String name = event.getName();
            Locale locale = event.getUserLocale().toLocale();
            // The pointer is copy like any other: the bundle speaks it in the
            // user's language, falling back to the configured default.
            event.reply(bundleHolder.renderer()
                            .raw("message.pointer.dm", locale, Map.of()))
                    .setEphemeral(true).queue();

            String text = switch (name) {

                case "save" -> optionText(event, "url")
                        .map(value -> "/save " + value)
                        .orElse("/save");
                default -> "/" + name;

            };

            if (text != null) {
                sink.accept(new IncomingUpdate.TextMessage(user(event.getUser().getId()),
                        chatId(event.getUser().getId()), text, locale.getLanguage()));
            }

        }

        @Override
        public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {

            event.deferEdit().queue();
            hooks.put(event.getId(), new HookEntry(event.getHook(), Instant.now()));
            sink.accept(new IncomingUpdate.Callback(
                    user(event.getUser().getId()),
                    chatId(event.getUser().getId()),
                    event.getComponentId(),
                    event.getMessageId(),
                    event.getId(),
                    event.getUserLocale().toLocale().getLanguage()));

        }

        @Override
        public void onGenericMessage(@NotNull net.dv8tion.jda.api.events.message.GenericMessageEvent event) {

            if (!(event instanceof net.dv8tion.jda.api.events.message.MessageReceivedEvent received)) {
                return;
            }

            if (!received.getChannelType().isThread() && received.getMessage().getAuthor().isBot()) {
                return;
            }

            if (received.getChannelType() != net.dv8tion.jda.api.entities.channel.ChannelType.PRIVATE) {
                return;
            }

            String content = received.getMessage().getContentRaw();

            if (content.isBlank() || content.startsWith("/")) {
                return;
            }

            sink.accept(new IncomingUpdate.TextMessage(
                    user(received.getAuthor().getId()),
                    chatId(received.getAuthor().getId()),
                    content,
                    null));

        }

        private java.util.Optional<String> optionText(SlashCommandInteractionEvent event, String name) {
            var option = event.getOption(name);
            return option == null ? java.util.Optional.empty()
                    : java.util.Optional.of(option.getAsString());
        }

        private PlatformUser user(String id) {
            return new PlatformUser(Platform.DISCORD, id);
        }

        private String chatId(String userId) {
            return userId;
        }

    }

    public JDA jda() {
        return jda;
    }

}

