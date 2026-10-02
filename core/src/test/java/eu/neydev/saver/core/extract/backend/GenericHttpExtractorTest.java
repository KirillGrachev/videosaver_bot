package eu.neydev.saver.core.extract.backend;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.ProgressListener;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.security.SsrfGuard;
import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceBackend;
import eu.neydev.saver.core.source.SourceStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenGraph scraper contract against a real (but local) HTTP server: og tags,
 * JSON-LD, redirects, non-HTML answers, HTTP error classification and the size cap.
 * The SSRF guard runs with allow-private-networks because the test server IS loopback -
 * the guard itself has its own dedicated test.
 */
class GenericHttpExtractorTest {

    private static final Source SOURCE = new Source("fakesite", "FakeSite",
            List.of("fakesite.example"), SourceBackend.HTTP, SourceStatus.OK);

    private HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    private record Handler(int status, String contentType, byte[] body, String location) {
    }

    @BeforeEach
    void startServer() throws IOException {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);

        server.createContext("/", exchange -> {

            Handler handler = handlers.getOrDefault(exchange.getRequestURI().getPath(),
                    new Handler(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null));

            if (handler.location() != null) {

                exchange.getResponseHeaders().set("Location", handler.location());
                exchange.sendResponseHeaders(handler.status(), -1);
                exchange.close();
                return;

            }

            exchange.getResponseHeaders().set("Content-Type", handler.contentType());
            exchange.sendResponseHeaders(handler.status(), handler.body().length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(handler.body());
            }

        });

        server.start();

    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GenericHttpExtractor extractor() {
        return new GenericHttpExtractor(new SafeHttp(new SsrfGuard(true), Duration.ofSeconds(10)));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private ExtractionRequest request(Path workDir, String path, long maxBytes) {

        return new ExtractionRequest(SOURCE, uri(path), QualityPreset.BEST, workDir,
                maxBytes, 10, ProgressListener.NOOP);

    }

    private void page(String path, String html) {
        handlers.put(path, new Handler(200, "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8), null));
    }

    private void file(String path, String contentType, byte[] bytes) {
        handlers.put(path, new Handler(200, contentType, bytes, null));
    }

    @Test
    void ogVideoIsDownloadedAsVideo(@TempDir Path dir) {

        file("/clip.mp4", "video/mp4", new byte[]{1, 2, 3, 4});
        page("/watch", """
                <html><head>
                <meta property="og:title" content="Test Clip">
                <meta property="og:video:secure_url" content="/clip.mp4">
                <meta property="og:image" content="/poster.jpg">
                </head><body>hi</body></html>
                """);
        file("/poster.jpg", "image/jpeg", new byte[]{9, 9});

        ExtractionResult result = extractor().extract(request(dir, "/watch", 1_000_000));

        // The video wins over the poster image: candidates are ordered by intent.
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(result.title()).isEqualTo("Test Clip");
        assertThat(result.items().get(0).sizeBytes()).isEqualTo(4);

    }

    @Test
    void imageOnlyPagesDeliverEveryOgImage(@TempDir Path dir) {

        file("/a.png", "image/png", new byte[]{1});
        file("/b.png", "image/png", new byte[]{2});
        page("/gallery", """
                <html><head>
                <meta property="og:image" content="/a.png">
                <meta property="og:image" content="/b.png">
                <meta property="og:image" content="/a.png">
                </head></html>
                """);

        ExtractionResult result = extractor().extract(request(dir, "/gallery", 1_000_000));

        // Deduplication: /a.png appears twice in the meta but downloads once.
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).allMatch(item -> item.kind() == MediaKind.PHOTO);

    }

    @Test
    void theSameUrlAcrossMetaFamiliesDownloadsOnce(@TempDir Path dir) {

        file("/shared.jpg", "image/jpeg", new byte[]{1, 2});
        page("/dup", """
                <html><head>
                <meta property="og:image" content="/shared.jpg">
                <meta name="twitter:image" content="/shared.jpg">
                <script type="application/ld+json">
                {"@type":"ImageObject","contentUrl":"/shared.jpg"}
                </script>
                </head></html>
                """);

        ExtractionResult result = extractor().extract(request(dir, "/dup", 1_000_000));

        // Three meta families, one URL - the user must not receive x.jpg, x-2.jpg, x-3.jpg.
        assertThat(result.items()).hasSize(1);

    }

    @Test
    void srcsetLargestBeatsTheOgPreview(@TempDir Path dir) {

        // og:image is the 300px card, the srcset knows the 1600px original:
        // the scraper must deliver the largest, not the first thing it sees.
        file("/small.jpg", "image/jpeg", new byte[]{1});
        file("/big.jpg", "image/jpeg", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        page("/photo", """
                <html><head>
                <meta property="og:image" content="/small.jpg">
                </head><body>
                <img src="/small.jpg" srcset="/small.jpg 300w, /big.jpg 1600w">
                </body></html>
                """);

        ExtractionResult result = extractor().extract(request(dir, "/photo", 1_000_000));

        assertThat(result.items()).isNotEmpty();
        assertThat(result.items().get(0).file().getFileName().toString()).isEqualTo("big.jpg");

    }

    @Test
    void jsonLdVideoObjectIsUsed(@TempDir Path dir) {

        file("/movie.mp4", "video/mp4", new byte[]{5, 5, 5});
        page("/jsonld", """
                <html><head>
                <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"VideoObject",
                 "name":"LD Clip","contentUrl":"/movie.mp4"}
                </script>
                </head></html>
                """);

        ExtractionResult result = extractor().extract(request(dir, "/jsonld", 1_000_000));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).kind()).isEqualTo(MediaKind.VIDEO);

    }

    @Test
    void redirectsAreFollowed(@TempDir Path dir) {

        handlers.put("/hop", new Handler(302, "text/plain", new byte[0], "/watch2"));
        file("/clip2.mp4", "video/mp4", new byte[]{7});
        page("/watch2", """
                <html><head><meta property="og:video" content="/clip2.mp4"></head></html>
                """);

        ExtractionResult result = extractor().extract(request(dir, "/hop", 1_000_000));

        assertThat(result.items()).hasSize(1);

    }

    @Test
    void aDirectFileUrlDownloadsAsIs(@TempDir Path dir) {

        file("/raw/photo.jpg", "image/jpeg", new byte[]{1, 2});

        ExtractionResult result = extractor().extract(request(dir, "/raw/photo.jpg", 1_000_000));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).kind()).isEqualTo(MediaKind.PHOTO);

    }

    @Test
    void manifestOnlyPagesAreRejectedForYtDlp(@TempDir Path dir) {

        page("/stream", """
                <html><head>
                <meta property="og:video" content="/index.m3u8">
                </head></html>
                """);
        file("/index.m3u8", "application/vnd.apple.mpegurl",
                "#EXTM3U".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> extractor().extract(request(dir, "/stream", 1_000_000)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.UNSUPPORTED);

    }

    @Test
    void httpErrorsBecomeCategories(@TempDir Path dir) {

        handlers.put("/gone", new Handler(404, "text/plain", "nope".getBytes(StandardCharsets.UTF_8), null));
        handlers.put("/walled", new Handler(403, "text/plain", "no".getBytes(StandardCharsets.UTF_8), null));

        assertThatThrownBy(() -> extractor().extract(request(dir, "/gone", 1_000_000)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.NOT_FOUND);

        assertThatThrownBy(() -> extractor().extract(request(dir, "/walled", 1_000_000)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.LOGIN_REQUIRED);

    }

    @Test
    void theSizeCapStopsRunawayDownloads(@TempDir Path dir) {

        file("/huge.mp4", "video/mp4", new byte[10_000]);
        page("/big", """
                <html><head><meta property="og:video" content="/huge.mp4"></head></html>
                """);

        assertThatThrownBy(() -> extractor().extract(request(dir, "/big", 1_000)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.TOO_LARGE);

    }

    @Test
    void xmlTypedFilesAreDownloadedNotScraped(@TempDir Path dir) {

        // A .pom/.svg/.xml answer is a FILE, not a page: the strict isHtml() check
        // must send it down the direct path (regression: contains("xml") scraping).
        file("/data/feed.xml", "application/xml", "<feed/>".getBytes(StandardCharsets.UTF_8));

        ExtractionResult result = extractor().extract(request(dir, "/data/feed.xml", 1_000_000));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).kind()).isEqualTo(MediaKind.DOCUMENT);

    }

    @Test
    void pagesWithoutMediaAreNotFound(@TempDir Path dir) {

        page("/empty", "<html><head><title>Blog</title></head><body>just text</body></html>");

        assertThatThrownBy(() -> extractor().extract(request(dir, "/empty", 1_000_000)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.NOT_FOUND);

    }

    @Test
    void directLinkBackendRefusesHtmlPages(@TempDir Path dir) {

        page("/page", "<html><body>hi</body></html>");

        DirectLinkExtractor direct =
                new DirectLinkExtractor(new SafeHttp(new SsrfGuard(true), Duration.ofSeconds(10)));

        assertThatThrownBy(() -> direct.extract(request(dir, "/page", 1_000_000)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.UNSUPPORTED);

    }

    @Test
    void directLinkBackendClassifiesByContentType(@TempDir Path dir) {

        file("/media/file-without-extension", "audio/mpeg", new byte[]{1, 2, 3});

        DirectLinkExtractor direct =
                new DirectLinkExtractor(new SafeHttp(new SsrfGuard(true), Duration.ofSeconds(10)));

        ExtractionResult result = direct.extract(request(dir, "/media/file-without-extension", 1_000_000));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).kind()).isEqualTo(MediaKind.AUDIO);
        assertThat(Files.exists(result.items().get(0).file())).isTrue();

    }

}
