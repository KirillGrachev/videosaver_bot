package eu.neydev.saver.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibResolverTest {

    @TempDir
    Path tempDir;

    private static byte[] jarBytes(String name) {
        // a minimal valid jar is not needed: the resolver works with bytes and sha
        return ("fake-jar-" + name).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesManifestSkippingComments() throws Exception {

        InputStream in = new ByteArrayInputStream("""
                # comment
                g/a/1.0/a-1.0.jar|abc
                g/b/2.0/b-2.0.jar|def
                """.getBytes(StandardCharsets.UTF_8));
        List<LibResolver.Entry> entries = LibResolver.parseManifest(in);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).relPath()).isEqualTo("g/a/1.0/a-1.0.jar");
        assertThat(entries.get(1).sha256()).isEqualTo("def");

    }

    @Test
    void downloadsMissingAndVerifiesSha() throws Exception {

        byte[] payload = jarBytes("a");
        String sha = LibResolver.sha256(writeTemp(payload));
        AtomicInteger hits = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/maven2/g/a/1.0/a-1.0.jar", exchange -> {

            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();

        });

        server.start();

        try {

            Path libs = tempDir.resolve("libs");
            LibResolver resolver = new LibResolver(libs,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2");
            List<Path> resolved = resolver.resolve(
                    List.of(new LibResolver.Entry("g/a/1.0/a-1.0.jar", sha)));

            assertThat(resolved).hasSize(1);
            assertThat(Files.readAllBytes(resolved.get(0))).isEqualTo(payload);
            assertThat(hits.get()).isEqualTo(1);

            // a repeated resolve does not hit the network
            resolver.resolve(List.of(new LibResolver.Entry("g/a/1.0/a-1.0.jar", sha)));
            assertThat(hits.get()).isEqualTo(1);

        } finally {
            server.stop(0);
        }

    }

    @Test
    void rejectsCorruptedDownload() throws Exception {

        byte[] payload = jarBytes("bad");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/maven2/g/a/1.0/a-1.0.jar", exchange -> {

            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();

        });

        server.start();

        try {
            LibResolver resolver = new LibResolver(tempDir.resolve("libs2"),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2");
            assertThatThrownBy(() -> resolver.resolve(
                    List.of(new LibResolver.Entry("g/a/1.0/a-1.0.jar", "0".repeat(64)))))
                    .hasMessageContaining("Checksum mismatch");
        } finally {
            server.stop(0);
        }

    }

    private Path writeTemp(byte[] bytes) throws Exception {

        Path file = tempDir.resolve("sample.bin");
        Files.write(file, bytes);

        return file;

    }

}

