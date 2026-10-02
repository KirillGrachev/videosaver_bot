package eu.neydev.saver.core.command;

import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.download.JobManager;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.i18n.MessageBundleHolder;
import eu.neydev.saver.core.text.RichText;
import eu.neydev.saver.core.util.ByteFormat;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The message set of the DOWNLOAD lifecycle, split out of {@link ReplyBuilder}: these
 * are called from job workers with explicit platform/chat/locale (no Interaction
 * exists on a worker thread), while ReplyBuilder serves the dialog layer. Keeping the
 * two families apart is what stops the reply factory from becoming a god class again.
 */
public final class JobReplies {

    private final MessageBundleHolder holder;
    private final MenuFactory menus;

    public JobReplies(MessageBundleHolder holder, MenuFactory menus) {
        this.holder = holder;
        this.menus = menus;
    }

    private RichText text(String key, Locale locale, Map<String, Object> params) {
        return holder.renderer().rich(key, locale, params);
    }

    private RichText text(String key, Locale locale) {
        return holder.renderer().rich(key, locale);
    }

    /** Callbacks EDIT the pressed message; job statuses edit the ack; typed replies send. */
    public static OutboundMessage editOrSend(Platform platform, String chatId,
                                             @Nullable String messageId, RichText text,
                                             InlineKeyboard keyboard) {

        if (messageId != null) {
            return new OutboundMessage.Edit(platform, chatId, messageId, text, keyboard);
        }

        return new OutboundMessage.Send(platform, chatId, text, keyboard);

    }

    public OutboundMessage.Send jobQueued(Platform platform, String chatId, Locale locale,
                                          String host) {

        return new OutboundMessage.Send(platform, chatId,
                text("message.status.queued", locale, Map.of("host", host)),
                menus.stopButton(locale));

    }

    public OutboundMessage.Edit jobProgress(Platform platform, String chatId, String messageId,
                                            Locale locale, int percent) {

        return new OutboundMessage.Edit(platform, chatId, messageId,
                text("message.status.progress", locale, Map.of("percent", percent)),
                menus.stopButton(locale));

    }

    /** Gallery progress: no percents here, only "N files done" - honest indeterminacy. */
    public OutboundMessage.Edit jobFilesProgress(Platform platform, String chatId,
                                                 String messageId, Locale locale, int filesDone) {

        return new OutboundMessage.Edit(platform, chatId, messageId,
                text("message.status.files", locale, Map.of("count", filesDone)),
                menus.stopButton(locale));

    }

    public OutboundMessage jobDone(Platform platform, String chatId, @Nullable String messageId,
                                   Locale locale, int itemCount, long bytes, Duration took,
                                   @Nullable String warningKey) {

        RichText done = text("message.status.done", locale, Map.of(
                "count", itemCount,
                "size", ByteFormat.human(bytes),
                "seconds", Math.max(1, took.toSeconds())));

        if (warningKey != null) {
            done = RichText.parse(done.toPlainText() + "\n"
                    + holder.renderer().raw(warningKey, locale));
        }

        return editOrSend(platform, chatId, messageId, done, InlineKeyboard.empty());

    }

    public OutboundMessage jobFailed(Platform platform, String chatId,
                                     @Nullable String messageId, Locale locale,
                                     ExtractionException.Category category) {

        return editOrSend(platform, chatId, messageId,
                text("message.error.cat." + category.id(), locale),
                InlineKeyboard.empty());

    }

    public OutboundMessage cancelled(Platform platform, String chatId,
                                     @Nullable String messageId, Locale locale) {

        return editOrSend(platform, chatId, messageId,
                text("message.error.cat.cancelled", locale), InlineKeyboard.empty());

    }

    /** The oversized-file fallback: an honest refusal plus the direct link. */
    public OutboundMessage.Send linkFallback(Platform platform, String chatId, Locale locale,
                                             String url, long sizeBytes, long capBytes) {

        return new OutboundMessage.Send(platform, chatId,
                text("message.error.too_large_link", locale, Map.of(
                        "size", ByteFormat.human(sizeBytes),
                        "cap", ByteFormat.human(capBytes),
                        "url", url)),
                InlineKeyboard.of(List.of(
                        InlineKeyboard.KeyboardButton.url(
                                holder.renderer().raw("button.open_link", locale), url))));

    }

    /** Submit-time refusals (quotas, queue, disabled source...). */
    public OutboundMessage.Send rejection(Platform platform, String chatId, Locale locale,
                                          String reasonKey, Map<String, Object> params) {

        return new OutboundMessage.Send(platform, chatId, text(reasonKey, locale, params),
                InlineKeyboard.empty());

    }

    /** One aggregated refusal: message key, its render params and how many URLs it covers. */
    public record Refusal(String messageKey, Map<String, Object> params, int count) {
    }

    /** Several refused URLs in one message: one line per distinct reason. */
    public OutboundMessage.Send rejections(Platform platform, String chatId, Locale locale,
                                           List<Refusal> refusals) {

        StringBuilder sb = new StringBuilder();

        for (Refusal refusal : refusals) {

            if (!sb.isEmpty()) {
                sb.append('\n');
            }

            sb.append(holder.renderer().raw(refusal.messageKey(), locale, refusal.params()));

            if (refusal.count() > 1) {
                sb.append(" (\u00d7").append(refusal.count()).append(')');
            }

        }

        return new OutboundMessage.Send(platform, chatId, RichText.parse(sb.toString()),
                InlineKeyboard.empty());

    }

    /** A file whose extension is on the malware-guard blocklist. */
    public OutboundMessage.Send blockedType(Platform platform, String chatId, Locale locale,
                                            String fileName) {

        return new OutboundMessage.Send(platform, chatId,
                text("message.error.blocked_type", locale, Map.of("file", fileName)),
                InlineKeyboard.empty());

    }

    /** Anti-flood: the ONE reply a throttled user gets (then silence). */
    public OutboundMessage.Send flood(Platform platform, String chatId, Locale locale) {

        return new OutboundMessage.Send(platform, chatId,
                text("message.flood", locale), InlineKeyboard.empty());

    }

    /** The caption under every delivered file. */
    public RichText caption(Locale locale, @Nullable String title, String sourceTitle,
                            long sizeBytes, @Nullable Integer durationSeconds,
                            int index, int total, boolean truncated) {

        StringBuilder sb = new StringBuilder();

        if (title != null && !title.isBlank()) {
            sb.append('*').append(RichText.escapeValue(shorten(title, 180))).append('*');
        } else {
            sb.append('*').append(RichText.escapeValue(sourceTitle)).append('*');
        }

        sb.append('\n').append(holder.renderer().raw("message.caption.meta", locale, Map.of(
                "source", sourceTitle,
                "size", ByteFormat.human(sizeBytes),
                "duration", durationSeconds == null ? "-" : formatDuration(durationSeconds))));

        if (total > 1) {
            sb.append('\n').append(holder.renderer().raw("message.caption.index", locale,
                    Map.of("index", index, "total", total)));
        }

        if (truncated) {
            sb.append('\n').append(holder.renderer().raw("message.caption.truncated", locale,
                    Map.of("count", total)));
        }

        return RichText.parse(sb.toString());

    }

    public InlineKeyboard sourceLink(Locale locale, String url) {
        return menus.sourceLink(locale, url);
    }

    static String formatDuration(int seconds) {

        if (seconds < 60) {
            return seconds + "s";
        }

        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }

        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";

    }

    private static String shorten(String value, int max) {

        String oneLine = value.replaceAll("\\s+", " ").trim();

        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "\u2026";

    }

    /** Maps a submit-time refusal to its message key + params (one place, no drift). */
    public OutboundMessage.Send rejection(Platform platform, String chatId, Locale locale,
                                          JobManager.SubmitOutcome.Refused refused) {

        return rejection(platform, chatId, locale,
                refused.rejection().messageKey(), refused.params());

    }

}
