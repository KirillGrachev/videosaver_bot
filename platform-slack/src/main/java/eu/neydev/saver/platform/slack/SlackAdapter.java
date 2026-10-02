package eu.neydev.saver.platform.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.UpdateSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.media.MediaAttachment;

/**
 * A thin Slack transport: Socket Mode (websocket outbound, no public HTTPS needed)
 * and executing outbound commands via {@link SlackApiClient}.
 * Event mapping and rendering live in {@link SlackEventMapper} and {@link SlackRenderers}.
 *
 * <p>Every envelope must be acked by envelope_id; interactive envelopes
 * acked immediately (Slack expects a reply within 3 seconds), the business reply comes
 * a separate message via the outbound dispatcher.
 */
public final class SlackAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String botToken;
    private final String appToken;
    private final SlackApiClient client;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private Thread supervisor;

    public SlackAdapter(String botToken, String appToken) {
        this(botToken, appToken, new SlackApiClient(botToken));
    }

    public SlackAdapter(String botToken, String appToken, String apiBase) {
        this(botToken, appToken, new SlackApiClient(botToken, apiBase));
    }

    private SlackAdapter(String botToken, String appToken, SlackApiClient client) {
        this.botToken = botToken;
        this.appToken = appToken;
        this.client = client;
    }

    @Override
    public Platform platform() {
        return Platform.SLACK;
    }

    @Override
    public boolean isEnabled() {
        return botToken != null && !botToken.isBlank() && appToken != null && !appToken.isBlank();
    }

    @Override
    public void start(PlatformContext context) {

        UpdateSink sink = context.instrumentedSink(Platform.SLACK);
        running.set(true);

        supervisor = Thread.ofVirtual().name("slack-supervisor").start(() -> supervise(sink, context));
        log.info("Slack: Socket Mode started");

    }

    private void supervise(UpdateSink sink, PlatformContext context) {

        long backoff = 1_000;

        while (running.get()) {

            try {
                connectAndPump(sink, context);
                backoff = 1_000;
            } catch (Exception e) {

                log.warn("Slack socket failure, retry in {} ms: {}", backoff, e.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, 60_000);

            }

        }

    }

    private void connectAndPump(UpdateSink sink, PlatformContext context) throws Exception {

        String url = client.openSocketUrl(appToken);
        CountDownLatch closed = new CountDownLatch(1);
        StringBuilder buffer = new StringBuilder();

        WebSocket.Listener listener = new WebSocket.Listener() {

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {

                buffer.append(data);

                if (last) {
                    String envelope = buffer.toString();
                    buffer.setLength(0);
                    context.health().beat(Platform.SLACK);
                    handleEnvelope(envelope, sink, webSocket);
                }

                webSocket.request(1);
                return null;

            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closed.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                closed.countDown();
            }

        };

        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(url), listener).join();
        socket.set(ws);
        closed.await();

        if (running.get()) {
            throw new PlatformException("Slack: socket closed");
        }

    }

    private void handleEnvelope(String envelopeJson, UpdateSink sink, WebSocket ws) {

        try {

            JsonNode envelope = MAPPER.readTree(envelopeJson);
            String type = envelope.path("type").asText("");
            String envelopeId = envelope.path("envelope_id").asText(null);

            switch (type) {

                case "events_api" -> {
                    ack(ws, envelopeId, null);
                    SlackEventMapper.mapEvent(envelope.path("event")).ifPresent(sink::accept);
                }

                case "interactive" -> {
                    ack(ws, envelopeId, MAPPER.createObjectNode());
                    SlackEventMapper.mapInteractive(envelope.path("payload")).ifPresent(sink::accept);
                }

                default -> ack(ws, envelopeId, null);

            }

        } catch (Exception e) {
            log.warn("Slack: envelope not processed: {}", e.getMessage());
        }

    }

    private void ack(WebSocket ws, String envelopeId, JsonNode payload) {

        if (envelopeId == null || ws == null) {
            return;
        }

        ObjectNode node = MAPPER.createObjectNode();
        node.put("envelope_id", envelopeId);

        if (payload != null) {
            node.set("payload", payload);
        }

        ws.sendText(node.toString(), true);

    }

    @Override
    public void stop() {

        running.set(false);
        WebSocket ws = socket.get();

        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }

        if (supervisor != null) {
            supervisor.interrupt();
        }

    }

    /** Slack posts multi-file albums as one message (completeUploadExternal array). */
    @Override
    public boolean supportsMediaGroups() {
        return true;
    }

    @Override
    public SendResult execute(OutboundMessage message) {

        return switch (message) {

            case OutboundMessage.Send send -> {

                var response = client.call("chat.postMessage", Map.of(
                        "channel", send.chatId(),
                        "text", SlackRenderers.mrkdwn(send.text()),
                        "blocks", SlackRenderers.blocks(send.keyboard()).toString()));

                // Slack message ids are channel|ts - the same shape Edit/Delete expect.
                yield SendResult.of(
                        response.path("channel").asText(send.chatId())
                                + "|" + response.path("ts").asText(""));

            }

            case OutboundMessage.SendMedia media -> sendMedia(media);

            // Slack CAN post several files in one message: completeUploadExternal
            // takes an array - one album upload instead of N separate messages.
            case OutboundMessage.SendMediaGroup group -> sendMediaGroup(group);

            case OutboundMessage.Edit edit -> {
                String[] parts = edit.messageId().split("\\|", 2);
                client.call("chat.update", Map.of(
                        "channel", parts[0],
                        "ts", parts.length > 1 ? parts[1] : "0",
                        "text", SlackRenderers.mrkdwn(edit.text()),
                        "blocks", SlackRenderers.blocks(edit.keyboard()).toString()));
                yield SendResult.of(edit.messageId());
            }

            case OutboundMessage.Delete delete -> {
                String[] parts = delete.messageId().split("\\|", 2);
                client.call("chat.delete", Map.of(
                        "channel", parts[0],
                        "ts", parts.length > 1 ? parts[1] : "0"));
                yield SendResult.ack();
            }

            case OutboundMessage.AnswerCallback answer -> {
                if (!answer.text().isEmpty()) {
                    client.call("chat.postEphemeral", Map.of(
                            "channel", answer.chatId(),
                            "user", answer.interactionId(),
                            "text", answer.text()));
                }
                yield SendResult.ack();
            }

        };

    }

    /**
     * The modern three-step Slack upload: an external upload URL, a raw PUT, then
     * completeUploadExternal which also shares the file into the channel. The caption
     * rides along as initial_comment. KNOWN LIMITATION: completeUploadExternal does
     * not report the created message's ts back, so a media send acks without an id
     * and cannot be edited/deleted later (nothing in the job flow needs that today;
     * files.info would be the extra call if it ever does).
     */
    /** Uploads every file, then shares them all in ONE completeUploadExternal call. */
    private SendResult sendMediaGroup(OutboundMessage.SendMediaGroup send) {

        StringBuilder filesJson = new StringBuilder("[");

        for (MediaAttachment media : send.media()) {

            if (filesJson.length() > 1) {
                filesJson.append(',');
            }

            filesJson.append("{\"id\":\"").append(uploadOne(media)).append("\"}");

        }

        filesJson.append(']');

        java.util.Map<String, String> complete = new java.util.HashMap<>();
        complete.put("files", filesJson.toString());
        complete.put("channel_id", send.chatId());

        String comment = SlackRenderers.mrkdwn(send.caption());

        if (!comment.isBlank()) {
            complete.put("initial_comment", comment);
        }

        client.call("files.completeUploadExternal", complete);

        return SendResult.ack();

    }

    private SendResult sendMedia(OutboundMessage.SendMedia send) {

        MediaAttachment media = send.media();
        String fileId = uploadOne(media);

        String filesJson = "[{\"id\":\"" + fileId + "\",\"title\":\""
                + media.fileName().replace("\\", "").replace("\"", "") + "\"}]";

        java.util.Map<String, String> complete = new java.util.HashMap<>();
        complete.put("files", filesJson);
        complete.put("channel_id", send.chatId());

        String comment = SlackRenderers.mrkdwn(send.caption());

        if (!comment.isBlank()) {
            complete.put("initial_comment", comment);
        }

        client.call("files.completeUploadExternal", complete);

        return SendResult.ack();

    }

    /** Steps 1+2 of the three-step upload: get an upload URL and stream the bytes. */
    private String uploadOne(MediaAttachment media) {

        if (!java.nio.file.Files.isRegularFile(media.file())) {
            throw new PlatformException.PermanentDeliveryException(
                    "Slack: media file vanished before the send: " + media.file());
        }

        var step1 = client.call("files.getUploadURLExternal", Map.of(
                "filename", media.fileName(),
                "length", String.valueOf(media.sizeBytes())));

        String uploadUrl = step1.path("upload_url").asText(null);
        String fileId = step1.path("file_id").asText(null);

        if (uploadUrl == null || fileId == null) {
            throw new PlatformException(
                    "Slack: getUploadURLExternal without upload_url/file_id");
        }

        client.put(uploadUrl, media.file());

        return fileId;

    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}

