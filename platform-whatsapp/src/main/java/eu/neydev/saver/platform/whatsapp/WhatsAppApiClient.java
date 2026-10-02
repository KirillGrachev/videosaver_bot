package eu.neydev.saver.platform.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.saver.core.api.PlatformException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * WhatsApp Cloud API (Graph) REST client: one HttpClient, JSON bodies,
 * classification of Meta errors (rate limit / permanent / transient).
 */
public final class WhatsAppApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE = "https://graph.facebook.com/v21.0/";

    private final String accessToken;
    private final String phoneId;
    private final String graphBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WhatsAppApiClient(String accessToken, String phoneId) {
        this(accessToken, phoneId, DEFAULT_BASE);
    }

    public WhatsAppApiClient(String accessToken, String phoneId, String graphBase) {

        this.accessToken = accessToken;
        this.phoneId = phoneId;
        this.graphBase = graphBase;

    }

    /** @return the platform message id ("wamid...") or null when the answer lacks it. */
    public String sendMessage(ObjectNode payload) {

        HttpRequest request = HttpRequest.newBuilder(URI.create(graphBase + phoneId + "/messages"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("WhatsApp: send interrupted", e);
        } catch (Exception e) {
            throw new PlatformException("WhatsApp: network: " + e.getMessage(), e);
        }

        JsonNode json;

        try {
            json = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new PlatformException("WhatsApp: not JSON in response", e);
        }

        if (json.has("error")) {
            throw classify(json.path("error"));
        }

        return json.path("messages").path(0).path("id").asText(null);

    }

    /**
     * Uploads a media file to {phoneId}/media (multipart) and returns the media id
     * for a subsequent messages payload. The Cloud API caps media at 16 MB and
     * documents at 100 MB - the core size policy keeps us under both.
     */
    public String uploadMedia(java.nio.file.Path file, String fileName, String mimeType) {

        String boundary = "----saverwa" + Long.toHexString(System.nanoTime());

        // Streamed multipart: a 100 MB document must not pass through the heap.
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"messaging_product\"\r\n\r\n"
                + "whatsapp\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName.replace("\"", "") + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";

        HttpRequest.BodyPublisher body;

        try {
            body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString(head, StandardCharsets.UTF_8),
                    HttpRequest.BodyPublishers.ofFile(file),
                    HttpRequest.BodyPublishers.ofString(tail, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new PlatformException("WhatsApp: cannot read media file: " + file, e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(graphBase + phoneId + "/media"))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("WhatsApp: media upload interrupted", e);
        } catch (Exception e) {
            throw new PlatformException("WhatsApp: media upload network: " + e.getMessage(), e);
        }

        JsonNode json;

        try {
            json = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new PlatformException("WhatsApp: media upload answer is not JSON", e);
        }

        if (json.has("error")) {
            throw classify(json.path("error"));
        }

        String id = json.path("id").asText(null);

        if (id == null) {
            throw new PlatformException("WhatsApp: media upload without id");
        }

        return id;

    }

    public ObjectMapper mapper() {
        return MAPPER;
    }

    private RuntimeException classify(JsonNode error) {

        int code = error.path("code").asInt(0);
        int subcode = error.path("error_subcode").asInt(0);

        if (code == 130429 || code == 4 || code == 17) {
            return new PlatformException.RateLimitedException("WhatsApp rate limit " + code, 2_000);
        }

        if (code == 131047 || code == 131049 || subcode == 2388088) {
            return new PlatformException.PermanentDeliveryException("WhatsApp: " + code);
        }

        return new PlatformException("WhatsApp error " + code + ": " + error.path("message").asText());

    }

}

