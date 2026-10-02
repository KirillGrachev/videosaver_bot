package eu.neydev.saver.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.media.MediaAttachment;

/**
 * A thin WhatsApp adapter: webhook input (Meta GET verification, shared-secret
 * and/or the X-Hub-Signature-256 signature) and executing outbound via
 * {@link WhatsAppApiClient}. Mapping and payload are in separate classes.
 *
 * <p>Platform limitations degrade deliberately: no editing/deleting
 * of a sent one (Edit -> a new send), no toasts (AnswerCallback -> no-op).
 */
public final class WhatsAppAdapter implements PlatformAdapter, WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppAdapter.class);
    private static final int MAX_WEBHOOK_BODY_BYTES = 512 * 1024;

    private final String verifyToken;
    private final String appSecret;
    private final WebhookSecret webhookSecret;
    private final WhatsAppApiClient client;
    private final AtomicReference<UpdateSink> sink = new AtomicReference<>();

    public WhatsAppAdapter(String accessToken, String id, String secret) {
        this(accessToken, id, secret, null);
    }

    public WhatsAppAdapter(String accessToken, String id, String secret, String graphBase) {

        String[] parts = id == null ? new String[0] : id.split(":");
        String phoneId = parts.length > 0 ? parts[0] : "";

        this.verifyToken = parts.length > 1 ? parts[1] : "";
        this.appSecret = parts.length > 2 ? parts[2] : null;
        this.webhookSecret = new WebhookSecret(secret);
        this.client = graphBase == null
                ? new WhatsAppApiClient(accessToken, phoneId)
                : new WhatsAppApiClient(accessToken, phoneId, graphBase);

    }

    @Override
    public Platform platform() {
        return Platform.WHATSAPP;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String path() {
        return "/hooks/whatsapp";
    }

    @Override
    public void start(PlatformContext context) {
        this.sink.set(context.instrumentedSink(Platform.WHATSAPP));
        log.info("WhatsApp: webhook mounted at {} (secret={})", path(),
                webhookSecret.required() ? "on" : "signature-only");
    }

    @Override
    public void stop() {
        sink.set(null);
    }

    @Override
    public @NotNull WebhookResponse handle(@NotNull WebhookRequest request) {

        if ("GET".equalsIgnoreCase(request.method())) {
            return handleVerify(request);
        }

        if (!authenticated(request)) {
            return WebhookResponse.denied("{\"error\":\"unauthenticated\"}");
        }

        if (request.body().length() > MAX_WEBHOOK_BODY_BYTES) {
            return new WebhookResponse(413, "application/json", "{\"error\":\"too large\"}");
        }

        dispatch(request.body());
        return WebhookResponse.ok("{\"status\":\"ok\"}");

    }

    private WebhookResponse handleVerify(WebhookRequest request) {

        Map<String, String> query = parseQuery(request.query());
        boolean ok = "subscribe".equals(query.get("hub.mode"))
                && WebhookSecret.constantTimeEquals(verifyToken, query.get("hub.verify_token"));

        return ok
                ? WebhookResponse.plain(query.getOrDefault("hub.challenge", ""))
                : WebhookResponse.denied("{\"error\":\"verify failed\"}");

    }

    private boolean authenticated(WebhookRequest request) {

        boolean secretOk = webhookSecret.matches(request);
        boolean signatureOk = appSecret != null && signatureValid(request);

        return secretOk || signatureOk;

    }

    private boolean signatureValid(WebhookRequest request) {

        String header = request.headers().get("x-hub-signature-256");

        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of()
                    .formatHex(mac.doFinal(request.body().getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    header.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }

    }

    private void dispatch(String body) {

        UpdateSink current = sink.get();

        if (current == null) {
            return;
        }

        try {

            JsonNode root = client.mapper().readTree(body);

            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    for (JsonNode message : change.path("value").path("messages")) {
                        WhatsAppEventMapper.mapMessage(message).ifPresent(current::accept);
                    }
                }
            }

        } catch (Exception e) {
            log.warn("WhatsApp: could not parse webhook body: {}", e.getMessage());
        }

    }

    /**
     * WhatsApp has NO message editing: the adapter answers an Edit with a fresh
     * message. Declaring this honestly lets the job manager suppress progress
     * edits instead of spamming the user with "42%... 63%... 100%".
     */
    @Override
    public boolean supportsEdits() {
        return false;
    }

    @Override
    public SendResult execute(OutboundMessage message) {
        return switch (message) {
            case OutboundMessage.Send send -> SendResult.of(
                    client.sendMessage(WhatsAppPayloadBuilder.buildSend(
                            send.chatId(), send.text(), send.keyboard())));
            case OutboundMessage.SendMedia media -> sendMedia(media);

            case OutboundMessage.SendMediaGroup ignored -> throw new IllegalStateException(
                    "WhatsApp: SendMediaGroup must be expanded by the outbound dispatcher");
            // WhatsApp has no message editing: a "changed" menu is a new message.
            // That is a platform property, not laziness - the core menu flow still
            // works because every Edit carries the full new text.
            case OutboundMessage.Edit edit -> SendResult.of(
                    client.sendMessage(WhatsAppPayloadBuilder.buildSend(
                            edit.chatId(), edit.text(), edit.keyboard())));
            case OutboundMessage.Delete ignored -> SendResult.ack();
            case OutboundMessage.AnswerCallback ignored -> SendResult.ack();
        };
    }

    /**
     * Two-step media delivery: upload to {phoneId}/media, then reference the media id
     * in a messages payload. GIFs are documents when they are real .gif files and
     * videos when the source handed us an mp4 (WhatsApp animates mp4 in the player).
     */
    private SendResult sendMedia(OutboundMessage.SendMedia send) {

        MediaAttachment media = send.media();

        if (!java.nio.file.Files.isRegularFile(media.file())) {
            throw new PlatformException.PermanentDeliveryException(
                    "WhatsApp: media file vanished before the send: " + media.file());
        }

        String type = switch (media.kind()) {
            case PHOTO -> "image";
            case VIDEO -> "video";
            case AUDIO -> "audio";
            case GIF -> media.fileName().toLowerCase(java.util.Locale.ROOT).endsWith(".gif")
                    ? "document"
                    : "video";
            case DOCUMENT -> "document";
        };

        String mediaId = client.uploadMedia(media.file(), media.fileName(), media.mimeType());

        return SendResult.of(client.sendMessage(
                WhatsAppPayloadBuilder.buildMedia(send.chatId(), type, mediaId,
                        send.caption(), send.keyboard(), media.fileName())));

    }

    private static Map<String, String> parseQuery(String query) {

        Map<String, String> result = new java.util.HashMap<>();

        for (String pair : query.split("&")) {

            int eq = pair.indexOf('=');

            if (eq > 0) {
                result.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }

        }

        return result;

    }

}

