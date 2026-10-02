package eu.neydev.saver.platform.viber;

import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.UpdateSink;
import eu.neydev.saver.core.http.WebhookHandler;
import eu.neydev.saver.core.http.WebhookSecret;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.media.MediaAttachment;

/**
 * A thin Viber adapter: webhook input with a shared secret, webhook registration
 * at start, executing outbound via {@link ViberApiClient}.
 * Mapping and keyboard are in separate classes.
 *
 * <p>Viber has no message editing: Edit deliberately degrades
 * into a new send.
 */
public final class ViberAdapter implements PlatformAdapter, WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(ViberAdapter.class);
    private static final int MAX_WEBHOOK_BODY_BYTES = 512 * 1024;

    private final String publicUrl;
    private final WebhookSecret webhookSecret;
    private final ViberApiClient client;
    private final AtomicReference<UpdateSink> sink = new AtomicReference<>();

    public ViberAdapter(String token, String publicUrl, String secret) {
        this(token, publicUrl, secret, null);
    }

    public ViberAdapter(String token, String publicUrl, String secret, String apiBase) {
        this.publicUrl = publicUrl;
        this.webhookSecret = new WebhookSecret(secret);
        this.client = apiBase == null
                ? new ViberApiClient(token)
                : new ViberApiClient(token, apiBase);
    }

    @Override
    public Platform platform() {
        return Platform.VIBER;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String path() {
        return "/hooks/viber";
    }

    @Override
    public void start(PlatformContext context) {
        this.sink.set(context.instrumentedSink(Platform.VIBER));
        registerWebhook();
    }

    private void registerWebhook() {

        try {

            ObjectNode payload = client.mapper().createObjectNode();
            payload.put("url", webhookSecret.decorateUrl(publicUrl + path()));
            payload.putArray("event_types")
                    .add("message")
                    .add("conversation_started")
                    .add("subscribed")
                    .add("unsubscribed");

            client.call("set_webhook", payload);
            log.info("Viber: webhook registered at {}", publicUrl + path());

        } catch (RuntimeException e) {
            log.warn("Viber: set_webhook failed (you can set it manually): {}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        sink.set(null);
    }

    @Override
    public @NotNull WebhookResponse handle(@NotNull WebhookRequest request) {

        if (!webhookSecret.matches(request)) {
            return WebhookResponse.denied("{\"error\":\"unauthenticated\"}");
        }

        if (request.body().length() > MAX_WEBHOOK_BODY_BYTES) {
            return new WebhookResponse(413, "application/json", "{\"error\":\"too large\"}");
        }

        dispatch(request.body());
        return WebhookResponse.ok("{\"status\":\"ok\"}");

    }

    private void dispatch(String body) {

        UpdateSink current = sink.get();

        if (current == null) {
            return;
        }

        try {
            ViberEventMapper.mapEvent(client.mapper().readTree(body))
                    .ifPresent(current::accept);
        } catch (Exception e) {
            log.warn("Viber: could not parse webhook body: {}", e.getMessage());
        }

    }

    /** Viber has NO message editing: an Edit becomes a new message (see WhatsApp). */
    @Override
    public boolean supportsEdits() {
        return false;
    }

    @Override
    public SendResult execute(OutboundMessage message) {
        return switch (message) {
            case OutboundMessage.Send send -> send(send);
            // Viber has no message editing: a "changed" menu is a new message.
            case OutboundMessage.Edit edit -> send(new OutboundMessage.Send(
                    edit.platform(), edit.chatId(), edit.text(), edit.keyboard(), false));
            case OutboundMessage.Delete ignored -> SendResult.ack();
            case OutboundMessage.AnswerCallback ignored -> SendResult.ack();
            case OutboundMessage.SendMedia media -> sendMedia(media);

            case OutboundMessage.SendMediaGroup ignored -> throw new IllegalStateException(
                    "Viber: SendMediaGroup must be expanded by the outbound dispatcher");
        };
    }

    private SendResult send(OutboundMessage.Send send) {

        ObjectNode payload = client.mapper().createObjectNode();
        payload.put("receiver", send.chatId());
        payload.put("type", "text");
        payload.put("text", send.text().toPlainText());
        payload.put("min_api_version", 2);
        payload.set("keyboard", ViberKeyboardMapper.map(send.keyboard()));

        return tokenOf(client.call("send_message", payload));

    }

    /**
     * Viber delivers media BY URL: the file must be publicly reachable, which is what
     * the core's MediaLinkServer publishes (short-lived vault links). Without a public
     * webapp URL the bot degrades to a text reply with the source link - honest, and
     * configured by the operator, not discovered at send time.
     *
     * <p>Type mapping follows Viber's own constraints: picture is a STATIC image up to
     * 1 MB, video needs a duration and caps at 26 MB / 3 min, everything else is a
     * file (up to 50 MB). Animated GIFs go as files - Viber freezes GIFs in pictures.
     */
    private SendResult sendMedia(OutboundMessage.SendMedia send) {

        MediaAttachment media = send.media();
        String publicUrl = media.publicUrl();

        if (publicUrl == null || publicUrl.isBlank()) {

            // No public media endpoint: fall back to the source link as plain text.
            String fallback = send.caption().toPlainText()
                    + (media.sourceUrl() != null ? "\n" + media.sourceUrl() : "");

            ObjectNode payload = client.mapper().createObjectNode();
            payload.put("receiver", send.chatId());
            payload.put("type", "text");
            payload.put("text", fallback);
            payload.put("min_api_version", 2);

            return tokenOf(client.call("send_message", payload));

        }

        ObjectNode payload = client.mapper().createObjectNode();
        payload.put("receiver", send.chatId());
        payload.put("min_api_version", 2);
        payload.set("keyboard", ViberKeyboardMapper.map(send.keyboard()));

        String caption = send.caption().toPlainText();

        switch (media.kind()) {

            case PHOTO -> {
                payload.put("type", "picture");
                payload.put("text", caption);
                payload.put("media", publicUrl);
                // No thumbnail field: it is optional per the Viber docs, and pointing
                // it at the full image would make Viber fetch the same bytes twice.
            }

            case VIDEO -> {

                if (media.durationSeconds() == null) {

                    // Viber REQUIRES a duration for video messages; a generic scrape
                    // often has none - a file still delivers the bytes.
                    payload.put("type", "file");
                    payload.put("media", publicUrl);
                    payload.put("size", media.sizeBytes());
                    payload.put("file_name", media.fileName());

                } else {

                    payload.put("type", "video");
                    payload.put("media", publicUrl);
                    payload.put("size", media.sizeBytes());
                    payload.put("duration", media.durationSeconds() * 1000);

                }

                payload.put("text", caption);

            }

            case AUDIO, GIF, DOCUMENT -> {
                payload.put("type", "file");
                payload.put("media", publicUrl);
                payload.put("size", media.sizeBytes());
                payload.put("file_name", media.fileName());
                payload.put("document_type", fileExtension(media.fileName()));
                payload.put("text", caption);
            }

        }

        return tokenOf(client.call("send_message", payload));

    }

    private static String fileExtension(String fileName) {

        int dot = fileName.lastIndexOf('.');

        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)
                : "dat";

    }

    /**
     * The client has already classified every non-zero status into the honest
     * PlatformException subtype; here only the token remains.
     */
    private static SendResult tokenOf(com.fasterxml.jackson.databind.JsonNode response) {

        return SendResult.of(
                response.path("message_token").asText(null));

    }

}

