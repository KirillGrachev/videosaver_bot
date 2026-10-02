package eu.neydev.saver.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeProvisionerTest {

    @Test
    void parsesClassVersionToFeature() {

        assertThat(RuntimeProvisioner.featureOfClassVersion("52.0")).isEqualTo(8);
        assertThat(RuntimeProvisioner.featureOfClassVersion("61.0")).isEqualTo(17);
        assertThat(RuntimeProvisioner.featureOfClassVersion("65.0")).isEqualTo(21);
        assertThat(RuntimeProvisioner.featureOfClassVersion("garbage")).isEqualTo(8);
        assertThat(RuntimeProvisioner.currentFeatureVersion()).isGreaterThanOrEqualTo(21);

    }

    @Test
    void findsProvisionedRuntimeByMarker(@TempDir Path temp) throws Exception {

        File runtime = temp.resolve("runtime").toFile();
        runtime.mkdirs();
        File fakeJava = temp.resolve("runtime/bin/java").toFile();
        fakeJava.getParentFile().mkdirs();
        fakeJava.createNewFile();
        Files.write(new File(runtime, "java.txt").toPath(),
                fakeJava.getAbsolutePath().getBytes(StandardCharsets.UTF_8));

        assertThat(RuntimeProvisioner.findProvisioned(runtime)).isEqualTo(fakeJava);
        assertThat(RuntimeProvisioner.findProvisioned(temp.resolve("empty").toFile())).isNull();

    }

    @Test
    void forwardsEncodingFlagsWhenMarkerSet() {

        String previous = System.getProperty(ConsoleEncoding.MARKER);

        try {

            System.clearProperty(ConsoleEncoding.MARKER);
            assertThat(RuntimeProvisioner.forwardedEncodingOptions()).isEmpty();

            System.setProperty(ConsoleEncoding.MARKER, "1");
            assertThat(RuntimeProvisioner.forwardedEncodingOptions())
                    .contains("-Dstdout.encoding=UTF-8", "-Dsaver.utf8=1");

        } finally {
            if (previous != null) {
                System.setProperty(ConsoleEncoding.MARKER, previous);
            } else {
                System.clearProperty(ConsoleEncoding.MARKER);
            }
        }

    }

    @Test
    void parsesAdoptiumAssetJson() throws Exception {

        String json = "{\"binaries\":[{\"package\":{\"link\":\"https://github.com/x/y.zip\","
                + "\"checksum\":\"" + "a".repeat(64) + "\"}}]}";
        String[] asset = RuntimeProvisioner.parseAssetJson(json);
        assertThat(asset[0]).isEqualTo("https://github.com/x/y.zip");
        assertThat(asset[1]).isEqualTo("a".repeat(64));

    }

    @Test
    void externalDownloadCommandPerOs(@TempDir Path temp) throws Exception {

        File target = temp.resolve("jre.zip").toFile();
        assertThat(RuntimeProvisioner.externalDownloadCommand("windows", "https://x/y", target))
                .contains("powershell")
                .anyMatch(arg -> arg.contains("Invoke-WebRequest"));
        assertThat(RuntimeProvisioner.externalDownloadCommand("linux", "https://x/y", target))
                .startsWith("curl");

    }

    @Test
    void runtimeDirRespectsOverride(@TempDir Path temp) {

        String previous = System.getProperty("saver.runtime.dir");
        System.setProperty("saver.runtime.dir", temp.toString());

        try {
            assertThat(RuntimeProvisioner.runtimeDir(new File("."))).isEqualTo(temp.toFile());
        } finally {
            if (previous == null) {
                System.clearProperty("saver.runtime.dir");
            } else {
                System.setProperty("saver.runtime.dir", previous);
            }
        }

    }

    @Test
    void prefersPackageZipOverInstallerMsi() throws Exception {

        String json = "{\"binaries\":[{\"installer\":{\"link\":\"https://github.com/x/y.msi\","
                + "\"checksum\":\"" + "b".repeat(64) + "\"},"
                + "\"package\":{\"link\":\"https://github.com/x/y.zip\","
                + "\"checksum\":\"" + "a".repeat(64) + "\"}}]}";
        String[] asset = RuntimeProvisioner.parseAssetJson(json);
        assertThat(asset[0]).endsWith(".zip");
        assertThat(asset[1]).isEqualTo("a".repeat(64));

    }

}

