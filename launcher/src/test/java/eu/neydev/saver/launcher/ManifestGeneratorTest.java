package eu.neydev.saver.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesManifestForRepositoryLayout() throws Exception {

        Path libs = tempDir.resolve("libs");
        Path jar = libs.resolve("org/example/lib/1.0/lib-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, "content".getBytes(StandardCharsets.UTF_8));

        Path manifest = tempDir.resolve("out/libs-manifest.txt");
        ManifestGenerator.main(new String[]{libs.toString(), manifest.toString()});

        try (InputStream in = Files.newInputStream(manifest)) {
            List<LibResolver.Entry> entries = LibResolver.parseManifest(in);
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).relPath()).isEqualTo("org/example/lib/1.0/lib-1.0.jar");
            assertThat(entries.get(0).sha256()).isEqualTo(LibResolver.sha256(jar));
        }

    }

}

