package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.security.SsrfGuard;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;

/**
 * Shared HTTP plumbing for the built-in backends (OpenGraph scraper, direct links).
 * Three rules, applied on EVERY request and EVERY redirect hop:
 *
 * <ul>
 *   <li>{@link SsrfGuard} checks each URL before a connection is opened - redirects are
 *       followed manually precisely so a public URL cannot bounce us into the LAN;</li>
 *   <li>a browser-like User-Agent: half the open-graph targets serve bots an empty
 *       shell page and browsers the real content;</li>
 *   <li>hard caps: body size for parsing, file size while streaming (the download
 *       aborts the moment the cap is crossed, not after filling the disk).</li>
 * </ul>
 */
public final class SafeHttp {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER = 64 * 1024;

    public record Response(int status, URI finalUrl, @Nullable String contentType, byte[] body) {

        /**
         * Strictly a PAGE type. "contains xml" would misclassify real files served as
         * application/xml or image/svg+xml as pages to scrape; anything that is not
         * (x)html goes down the direct-download path instead.
         */
        public boolean isHtml() {

            String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');

            if (semi >= 0) {
                type = type.substring(0, semi).trim();
            }

            return type.equals("text/html") || type.equals("application/xhtml+xml");

        }

        public String bodyAsString() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

    }

    private final HttpClient client;
    private final SsrfGuard guard;
    private final Duration timeout;

    public SafeHttp(SsrfGuard guard, Duration timeout) {

        this.guard = guard;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

    }

    /** Fetches a page into memory (capped), following redirects under guard control. */
    public Response fetch(URI url, int maxBodyBytes, String backendId) {

        checkStableGuarded(url, backendId);
        URI current = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {

            HttpResponse<InputStream> response = send(current, backendId);
            int status = response.statusCode();

            if (status >= 300 && status < 400) {

                String location = response.headers().firstValue("location").orElse(null);
                closeQuietly(response);

                if (location == null || location.isBlank()) {
                    throw failure(status, backendId, current);
                }

                current = current.resolve(location);
                continue;

            }

            if (status != 200) {
                closeQuietly(response);
                throw failure(status, backendId, current);
            }

            try (InputStream in = response.body()) {

                byte[] body = readCapped(in, maxBodyBytes);
                String contentType = response.headers().firstValue("content-type").orElse(null);

                return new Response(status, current, contentType, body);

            } catch (IOException e) {
                throw new ExtractionException(Category.NETWORK, backendId,
                        "Read failed: " + e.getMessage(), e);
            }

        }

        throw new ExtractionException(Category.NETWORK, backendId,
                "Too many redirects for " + url);

    }

    /**
     * Streams a file to disk with a hard size cap and interrupt checks every buffer.
     * Returns the number of bytes written.
     *
     * @throws ExtractionException TOO_LARGE the moment the cap is crossed; CANCELLED on
     *                             interrupt; NETWORK on any transport failure. Partial
     *                             files are deleted here - the work dir stays clean.
     */
    public long download(URI url, Path destination, long maxBytes, String backendId) {

        checkStableGuarded(url, backendId);
        URI current = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {

            HttpResponse<InputStream> response = send(current, backendId);
            int status = response.statusCode();

            if (status >= 300 && status < 400) {

                String location = response.headers().firstValue("location").orElse(null);
                closeQuietly(response);

                if (location == null || location.isBlank()) {
                    throw failure(status, backendId, current);
                }

                current = current.resolve(location);
                continue;

            }

            if (status != 200) {
                closeQuietly(response);
                throw failure(status, backendId, current);
            }

            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);

            if (declared > maxBytes) {
                closeQuietly(response);
                throw new ExtractionException(Category.TOO_LARGE, backendId,
                        "File declares " + declared + " bytes, cap is " + maxBytes);
            }

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(destination,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] buffer = new byte[BUFFER];
                long written = 0;
                int read;

                while ((read = in.read(buffer)) != -1) {

                    if (Thread.currentThread().isInterrupted()) {
                        throw new ExtractionException(Category.CANCELLED, backendId, "Cancelled");
                    }

                    written += read;

                    if (written > maxBytes) {
                        throw new ExtractionException(Category.TOO_LARGE, backendId,
                                "File exceeded the " + maxBytes + " byte cap mid-download");
                    }

                    out.write(buffer, 0, read);

                }

                if (written == 0) {
                    throw new ExtractionException(Category.NOT_FOUND, backendId,
                            "Empty body from " + current);
                }

                return written;

            } catch (ExtractionException e) {
                deleteQuietly(destination);
                throw e;
            } catch (IOException e) {
                deleteQuietly(destination);
                throw new ExtractionException(Category.NETWORK, backendId,
                        "Download failed: " + e.getMessage(), e);
            }

        }

        throw new ExtractionException(Category.NETWORK, backendId,
                "Too many redirects for " + url);

    }

    private HttpResponse<InputStream> send(URI url, String backendId) {

        checkGuarded(url, backendId);

        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                .GET()
                .build();

        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException(Category.CANCELLED, backendId, "Interrupted");
        } catch (IOException e) {
            throw new ExtractionException(Category.NETWORK, backendId,
                    "Request failed: " + e.getMessage(), e);
        }

    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 1 << 16));
        byte[] buffer = new byte[BUFFER];
        int read;
        int total = 0;

        while (total < maxBytes && (read = in.read(buffer, 0, Math.min(BUFFER, maxBytes - total))) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }

        return out.toByteArray();

    }

    /**
     * Guard violations surface as UNSUPPORTED, not as a raw RuntimeException: the user
     * sees "the link is not supported/blocked" instead of a generic crash, and the
     * chain keeps its categorized-error contract for the fallback ladder.
     */
    private void checkStableGuarded(URI url, String backendId) {

        try {
            guard.checkStable(url);
        } catch (SsrfGuard.SsrfException e) {
            throw new ExtractionException(Category.UNSUPPORTED, backendId, e.getMessage());
        }

    }

    private void checkGuarded(URI url, String backendId) {

        try {
            guard.check(url);
        } catch (SsrfGuard.SsrfException e) {
            throw new ExtractionException(Category.UNSUPPORTED, backendId, e.getMessage());
        }

    }

    private static ExtractionException failure(int status, String backendId, URI url) {

        Category category = switch (status) {
            case 401, 403 -> Category.LOGIN_REQUIRED;
            case 404, 410 -> Category.NOT_FOUND;
            case 429 -> Category.RATE_LIMITED;
            case 451 -> Category.GEO_BLOCKED;
            default -> status >= 500 ? Category.NETWORK : Category.UNKNOWN;
        };

        return new ExtractionException(category, backendId,
                "HTTP " + status + " for " + url);

    }

    private static void closeQuietly(HttpResponse<InputStream> response) {

        try {
            response.body().close();
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }

    }

    private static void deleteQuietly(Path path) {

        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the vault sweeper will collect it
        }

    }

}
