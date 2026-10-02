package eu.neydev.saver.core.command.handlers;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.command.CommandHandler;
import eu.neydev.saver.core.command.Interaction;
import eu.neydev.saver.core.command.JobReplies;
import eu.neydev.saver.core.command.ReplyBuilder;
import eu.neydev.saver.core.conversation.ConversationStore;
import eu.neydev.saver.core.download.JobManager;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.security.UrlIntake;
import eu.neydev.saver.core.storage.UserSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The download entry points: the /save command, the stop button/command, and the
 * free-text path (a pasted link with no command at all - the way 90% of requests
 * actually arrive). Submission itself lives in {@link JobManager}; this layer only
 * turns text into submit calls and refusals into localized messages.
 *
 * <p>Two anti-spam rules live here: a paste-bomb of five links produces at most ONE
 * refusal message per distinct reason (not five), and {@code /save audio <url>} lets a
 * user pick the quality FOR THIS REQUEST without touching their default.
 */
public final class DownloadHandlers {

    private final JobManager jobManager;
    private final ReplyBuilder replies;
    private final JobReplies jobReplies;
    private final ConversationStore conversations;
    private final UrlIntake intake;

    public DownloadHandlers(JobManager jobManager, ReplyBuilder replies, JobReplies jobReplies,
                            ConversationStore conversations, UrlIntake intake) {
        this.jobManager = jobManager;
        this.replies = replies;
        this.jobReplies = jobReplies;
        this.conversations = conversations;
        this.intake = intake;
    }

    /** /save [quality] [url] - quality token is optional and applies to this request only. */
    public CommandHandler save() {

        return interaction -> {

            String args = interaction.args().trim();

            if (args.isEmpty()) {

                conversations.begin(interaction.update().user(),
                        ConversationStore.State.AWAITING_URL);

                return List.of(replies.urlPrompt(interaction));

            }

            QualityPreset override = null;
            String rest = args;
            String firstToken = args.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);

            try {
                override = QualityPreset.fromId(firstToken);
                rest = args.substring(firstToken.length()).trim();
            } catch (IllegalArgumentException ignored) {
                // not a preset token - the whole args string is the URL part
            }

            List<String> urls = intake.extractUrls(rest);

            if (urls.isEmpty()) {

                return List.of(jobReplies.rejection(platform(interaction),
                        interaction.chatId(), interaction.locale(),
                        "message.error.invalid_url", Map.of()));

            }

            return submitAll(interaction, urls,
                    override != null ? override : interaction.settings().quality());

        };

    }

    /** /stop and the stop button under the queued status message. */
    public CommandHandler stop() {

        return interaction -> {

            int cancelled = jobManager.cancel(
                    interaction.update().user(), interaction.chatId());

            return cancelled == 0
                    ? List.of(replies.cancelNone(interaction))
                    : List.of(replies.cancelDone(interaction, cancelled));

        };

    }

    /** The AWAITING_URL dialog answer. */
    public List<OutboundMessage> handleAwaitingUrl(Interaction interaction, String text) {

        conversations.clear(interaction.update().user());

        List<String> urls = intake.extractUrls(text);

        if (urls.isEmpty()) {
            return List.of(replies.hintNoUrl(interaction));
        }

        return submitAll(interaction, urls, interaction.settings().quality());

    }

    /** Free text with links inside - the primary flow. */
    public List<OutboundMessage> handleFreeText(Interaction interaction, String text) {

        List<String> urls = intake.extractUrls(text);

        if (urls.isEmpty()) {
            return List.of();
        }

        return submitAll(interaction, urls, interaction.settings().quality());

    }

    private List<OutboundMessage> submitAll(Interaction interaction, List<String> urls,
                                            QualityPreset quality) {

        Platform platform = platform(interaction);
        Locale locale = interaction.locale();

        List<OutboundMessage> out = new ArrayList<>();
        // reasonKey -> refusal (params of the first occurrence + count)
        Map<String, JobReplies.Refusal> refusals = new LinkedHashMap<>();

        for (String url : urls) {

            JobManager.SubmitOutcome outcome = jobManager.submit(
                    interaction.update().user(), interaction.chatId(), url,
                    quality, locale);

            switch (outcome) {

                // Accepted jobs send their own status message from the JobManager:
                // adding another one here would double the dialog line.
                case JobManager.SubmitOutcome.Accepted ignored -> {
                }

                case JobManager.SubmitOutcome.InvalidUrl ignored -> mergeRefusal(refusals,
                        "message.error.invalid_url", Map.of());

                case JobManager.SubmitOutcome.Refused refused -> mergeRefusal(refusals,
                        refused.rejection().messageKey(), refused.params());

            }

        }

        if (!refusals.isEmpty()) {

            // One aggregated message, not one per link: a paste-bomb of five dead
            // links must not answer with five identical refusals.
            out.add(jobReplies.rejections(platform, interaction.chatId(), locale,
                    List.copyOf(refusals.values())));

        }

        return out;

    }

    private static void mergeRefusal(Map<String, JobReplies.Refusal> refusals,
                                     String key, Map<String, Object> params) {

        refusals.merge(key, new JobReplies.Refusal(key, params, 1),
                (existing, added) -> new JobReplies.Refusal(key, existing.params(),
                        existing.count() + 1));

    }

    private static Platform platform(Interaction interaction) {
        return interaction.update().user().platform();
    }

    /** Kept for handlers that already hold settings (the router's throttle reply). */
    public UserSettings settingsOf(Interaction interaction) {
        return interaction.settings();
    }

}
