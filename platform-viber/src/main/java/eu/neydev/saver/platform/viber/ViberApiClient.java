package eu.neydev.saver.platform.viber;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.saver.core.api.PlatformException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Viber Bot API REST client: one HttpClient, JSON bodies, the
 * X-Viber-Auth-Token, response status classification.
 */
public final class ViberApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE = "https://chatapi.viber.com/pa/";

    private final String token;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ViberApiClient(String token) {
        this(token, DEFAULT_BASE);
    }

    public ViberApiClient(String token, String apiBase) {
        this.token = token;
        this.apiBase = apiBase;
    }

    public JsonNode call(String method, ObjectNode payload) {

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + method))
                .timeout(Duration.ofSeconds(15))
                .header("X-Viber-Auth-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response;

        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Viber: interrupted " + method, e);
        } catch (Exception e) {
            throw new PlatformException("Viber: network " + method + ": " + e.getMessage(), e);
        }

        JsonNode json;

        try {
            json = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new PlatformException("Viber: not JSON from " + method, e);
        }

        int status = json.path("status").asInt(0);

        if (status != 0) {
            throw classify(status, json.path("status_message").asText(""));
        }

        return json;

    }

    public ObjectMapper mapper() {
        return MAPPER;
    }

    private RuntimeException classify(int status, String message) {

        if (status == 6 || status == 11) {
            return new PlatformException.RateLimitedException("Viber " + status, 1_500);
        }

        // 3 - user not found / 13 - conversation required.
        // 201/202/203 - the receiver is gone, blocked us or has no Viber: retrying a
        // media send at these would burn the dispatcher's ladder for nothing.
        if (status == 3 || status == 13 || status == 201 || status == 202 || status == 203) {
            return new PlatformException.PermanentDeliveryException("Viber " + status + ": " + message);
        }

        return new PlatformException("Viber error " + status + ": " + message);

    }

}

