package eu.neydev.saver.launcher;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Build-time tool (Java 8): walks {@code libs/} in the repository layout
 * and writes the manifest {@code relpath|sha256}. Called by the exec plugin in the app module
 * after dependency:copy-dependencies - the manifest always matches the jars exactly.
 */
public final class ManifestGenerator {

    public static void main(String[] args) throws IOException {

        Path libsDir = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Files.createDirectories(out.getParent());

        List<Path> jars = Launcher.listJars(libsDir);
        Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8);

        try {
            writer.write("# saver-bot libs manifest: relpath|sha256\n");
            for (Path jar : jars) {
                String rel = libsDir.toAbsolutePath()
                        .relativize(jar.toAbsolutePath()).toString().replace('\\', '/');
                writer.write(rel + "|" + LibResolver.sha256(jar) + "\n");
            }
        } finally {
            writer.close();
        }

        System.out.println("[manifest] libraries: " + jars.size());

    }

}

