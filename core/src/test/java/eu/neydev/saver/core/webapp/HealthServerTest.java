package eu.neydev.saver.core.webapp;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.TestBot;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.media.MediaKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ops-surface contract: /healthz answers with engine state, the token (when set)
 * guards BOTH service endpoints, and /media serves exactly what MediaLinkServer
 * published - nothing else, and not for long.
 */
class HealthServerTest {

    private AppConfig configWithWebapp(Path dir, String token) {

        AppConfig base = TestBot.config(dir, 2, 20, 2_000_000_000L,
                TestBot.defaultDelivery(), 1_000_000_000L);

        return new AppConfig(base.storage(), base.downloader(), base.limits(),
                base.delivery(), base.pipeline(),
                new AppConfig.WebApp(true, "127.0.0.1", 0, "https://saver.example",
                        Duration.ofMinutes(5), token),
                base.locale(), base.sources(), base.community(), base.platforms(),
                base.ownerKeys(), base.status());

    }

    private static HttpResponse<String> get(String url, String bearerToken) throws Exception {

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET();

        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

    }

    @Test
    void healthzReportsTheEngineState(@TempDir Path dir) throws Exception {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            HealthServer server = new HealthServer(configWithWebapp(dir, null),
                    bot.metrics, bot.health(), new MediaLinkServer(null, Duration.ofMinutes(1)),
                    bot.jobManager, bot.storage.users()::count, bot.clock);
            server.start();

            try {

                HttpResponse<String> response =
                        get("http://127.0.0.1:" + server.boundPort() + "/healthz", null);

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body())
                        .contains("\"status\":\"ok\"")
                        .contains("\"backends\"")
                        .contains("\"engine\"")
                        .contains("\"jobsActive\"");

            } finally {
                server.close();
            }

        }

    }

    @Test
    void theTokenGuardsHealthzAndMetrics(@TempDir Path dir) throws Exception {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            HealthServer server = new HealthServer(configWithWebapp(dir, "s3cret"),
                    bot.metrics, bot.health(), new MediaLinkServer(null, Duration.ofMinutes(1)),
                    bot.jobManager, bot.storage.users()::count, bot.clock);
            server.start();

            try {

                String base = "http://127.0.0.1:" + server.boundPort();

                assertThat(get(base + "/healthz", null).statusCode()).isEqualTo(403);
                assertThat(get(base + "/metrics", null).statusCode()).isEqualTo(403);
                assertThat(get(base + "/healthz", "wrong").statusCode()).isEqualTo(403);

                assertThat(get(base + "/healthz", "s3cret").statusCode()).isEqualTo(200);
                assertThat(get(base + "/metrics?token=s3cret", null).statusCode()).isEqualTo(200);

            } finally {
                server.close();
            }

        }

    }

    @Test
    void mediaLinksServePublishedFilesOnly(@TempDir Path dir) throws Exception {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            MediaLinkServer links = new MediaLinkServer("https://saver.example",
                    Duration.ofMinutes(5));

            HealthServer server = new HealthServer(configWithWebapp(dir, null),
                    bot.metrics, bot.health(), links,
                    bot.jobManager, bot.storage.users()::count, bot.clock);
            server.start();

            try {

                Path file = dir.resolve("published.bin");
                Files.write(file, "payload-bytes".getBytes());

                String published = links.publish(file, "published.bin",
                        "application/octet-stream").orElseThrow();
                String token = published.substring(published.lastIndexOf('/') + 1);

                String base = "http://127.0.0.1:" + server.boundPort();

                HttpResponse<String> ok = get(base + "/media/" + token, null);
                assertThat(ok.statusCode()).isEqualTo(200);
                assertThat(ok.body()).isEqualTo("payload-bytes");

                assertThat(get(base + "/media/not-a-token", null).statusCode()).isEqualTo(404);
                assertThat(get(base + "/media/" + token + "/escape", null).statusCode())
                        .isEqualTo(404);

            } finally {
                server.close();
            }

        }

    }

    @Test
    void webhooksAreRoutedToTheirHandler(@TempDir Path dir) throws Exception {

        try (TestBot bot = TestBot.standard(dir, MediaKind.VIDEO, 100)) {

            HealthServer server = new HealthServer(configWithWebapp(dir, null),
                    bot.metrics, bot.health(), new MediaLinkServer(null, Duration.ofMinutes(1)),
                    bot.jobManager, bot.storage.users()::count, bot.clock);

            server.registerWebhook(new eu.neydev.saver.core.http.WebhookHandler() {

                @Override
                public String path() {
                    return "/hooks/test";
                }

                @Override
                public eu.neydev.saver.core.http.WebhookHandler.WebhookResponse handle(
                        eu.neydev.saver.core.http.WebhookHandler.WebhookRequest request) {
                    return eu.neydev.saver.core.http.WebhookHandler.WebhookResponse
                            .plain("hooked:" + request.method());
                }

            });

            server.start();

            try {

                HttpResponse<String> response =
                        get("http://127.0.0.1:" + server.boundPort() + "/hooks/test", null);

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo("hooked:GET");

            } finally {
                server.close();
            }

        }

    }

}
