package eu.neydev.saver.core.extract.backend;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.extract.backend.ToolProvisioner.Arch;
import eu.neydev.saver.core.extract.backend.ToolProvisioner.Os;
import eu.neydev.saver.core.extract.backend.ToolProvisioner.Platform;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The self-provisioner contract: what gets downloaded, from where, and - above all -
 * that an unverifiable artifact NEVER reaches the tools directory. Every download runs
 * against a local HTTP server; the release endpoints are swapped through the
 * package-private {@link ToolProvisioner.ReleaseUrls} seam.
 */
class ToolProvisionerTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final Map<String, byte[]> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", exchange -> {

            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();

            byte[] body = routes.get(path);

            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();

        });

        server.setExecutor(null);
        server.start();

    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void serve(String path, byte[] body) {
        routes.put(path, body);
    }

    private int hits(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    /** Every artifact resolves to the local server, whatever its production URL is. */
    private record LocalUrls(String base) implements ToolProvisioner.ReleaseUrls {

        @Override
        public URI ytDlpBinary(String asset) {
            return URI.create(base + "/" + asset);
        }

        @Override
        public URI ytDlpChecksums() {
            return URI.create(base + "/SHA2-256SUMS");
        }

        @Override
        public URI ffmpegArchive(String asset) {
            return URI.create(base + "/" + asset);
        }

        @Override
        public URI ffmpegChecksums() {
            return URI.create(base + "/checksums.sha256");
        }

        @Override
        public URI galleryDlBinary(String asset) {
            return URI.create(base + "/" + asset);
        }

        @Override
        public URI galleryDlChecksums() {
            return URI.create(base + "/SHA256SUMS");
        }

    }

    private ToolProvisioner provisioner(Platform platform) {

        AppConfig.Downloader.Tools tools = new AppConfig.Downloader.Tools(
                "yt-dlp", "gallery-dl", "ffmpeg", "ffprobe", Duration.ZERO, true,
                tempDir.resolve("tools").toString());

        return new ToolProvisioner(tools, new MetricsRegistry(),
                new LocalUrls(base()), platform);

    }

    private static final Platform LINUX_X64 = new Platform(Os.LINUX, Arch.X64);
    private static final Platform WINDOWS_X64 = new Platform(Os.WINDOWS, Arch.X64);

    private static String sha256(byte[] bytes) {

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

    }

    private static String sums(String asset, byte[] body) {
        return sha256(body) + "  " + asset + "\n";
    }

    @Test
    void platformDetectionCoversTheSupportedMatrix() {

        assertThat(Platform.of("Linux", "amd64")).isEqualTo(LINUX_X64);
        assertThat(Platform.of("Linux", "x86_64")).isEqualTo(LINUX_X64);
        assertThat(Platform.of("Linux", "aarch64")).isEqualTo(new Platform(Os.LINUX, Arch.ARM64));
        assertThat(Platform.of("Windows Server 2022", "amd64")).isEqualTo(WINDOWS_X64);
        assertThat(Platform.of("Mac OS X", "aarch64").os()).isEqualTo(Os.UNSUPPORTED);
        assertThat(Platform.of("Linux", "riscv64").arch()).isEqualTo(Arch.UNSUPPORTED);
        assertThat(Platform.of(null, null).os()).isEqualTo(Os.UNSUPPORTED);

    }

    @Test
    void assetNamesFollowTheReleaseEndpoints() {

        assertThat(ToolProvisioner.ytDlpAsset(LINUX_X64)).isEqualTo("yt-dlp_linux");
        assertThat(ToolProvisioner.ytDlpAsset(new Platform(Os.LINUX, Arch.ARM64)))
                .isEqualTo("yt-dlp_linux_aarch64");
        assertThat(ToolProvisioner.ytDlpAsset(WINDOWS_X64)).isEqualTo("yt-dlp.exe");
        assertThat(ToolProvisioner.ytDlpAsset(new Platform(Os.WINDOWS, Arch.ARM64)))
                .isEqualTo("yt-dlp_arm64.exe");

        assertThat(ToolProvisioner.ffmpegAsset(LINUX_X64))
                .isEqualTo("ffmpeg-master-latest-linux64-gpl.tar.xz");
        assertThat(ToolProvisioner.ffmpegAsset(new Platform(Os.LINUX, Arch.ARM64)))
                .isEqualTo("ffmpeg-master-latest-linuxarm64-gpl.tar.xz");
        assertThat(ToolProvisioner.ffmpegAsset(WINDOWS_X64))
                .isEqualTo("ffmpeg-master-latest-win64-gpl.zip");

        // gallery-dl ships standalone binaries for Linux x64 and Windows x64 only
        assertThat(ToolProvisioner.galleryDlAsset(LINUX_X64)).isEqualTo("gallery-dl.bin");
        assertThat(ToolProvisioner.galleryDlAsset(WINDOWS_X64)).isEqualTo("gallery-dl.exe");
        assertThat(ToolProvisioner.galleryDlAsset(new Platform(Os.LINUX, Arch.ARM64))).isNull();
        assertThat(ToolProvisioner.ytDlpAsset(new Platform(Os.UNSUPPORTED, Arch.X64))).isNull();

    }

    @Test
    void sha256SumsParserTakesHashNamePairsAndIgnoresJunk() {

        Map<String, String> sums = ToolProvisioner.parseSha256Sums("""
                58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a  yt-dlp_linux
                b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc *yt-dlp_linux_aarch64
                not a checksum line
                short  name
                """);

        assertThat(sums).containsEntry("yt-dlp_linux",
                "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a");
        assertThat(sums).containsEntry("yt-dlp_linux_aarch64",
                "b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc");
        assertThat(sums).hasSize(2);

    }

    @Test
    void releaseAssetParserMapsNamesToDownloadUrls() {

        Map<String, String> assets = ToolProvisioner.parseReleaseAssets("""
                {"tag_name":"v1.32.14","assets":[
                  {"name":"gallery-dl.exe","browser_download_url":"https://cb/gallery-dl.exe"},
                  {"name":"SHA256SUMS","browser_download_url":"https://cb/SHA256SUMS"},
                  {"name":"broken","browser_download_url":""}]}
                """);

        assertThat(assets).containsEntry("gallery-dl.exe", "https://cb/gallery-dl.exe");
        assertThat(assets).containsEntry("SHA256SUMS", "https://cb/SHA256SUMS");
        assertThat(assets).hasSize(2);
        assertThat(ToolProvisioner.parseReleaseAssets("not json")).isEmpty();

    }

    @Test
    void ytDlpIsDownloadedVerifiedAndMadeExecutable() throws IOException {

        byte[] binary = "#!/bin/sh\necho fake-ytdlp\n".getBytes(StandardCharsets.UTF_8);

        serve("/yt-dlp_linux", binary);
        serve("/SHA2-256SUMS", sums("yt-dlp_linux", binary).getBytes(StandardCharsets.UTF_8));

        Path target = tempDir.resolve("tools").resolve("yt-dlp");
        AtomicInteger reprobes = new AtomicInteger();

        provisioner(LINUX_X64).install(
                Map.of("ytdlp", target.toString()), reprobes::incrementAndGet);

        assertThat(target).exists();
        assertThat(Files.isExecutable(target)).isTrue();
        assertThat(Files.readAllBytes(target)).isEqualTo(binary);
        assertThat(tempDir.resolve("tools").resolve("yt-dlp.part")).doesNotExist();
        assertThat(reprobes.get()).isEqualTo(1);

    }

    @Test
    void aChecksumMismatchRefusesTheInstall() throws IOException {

        byte[] binary = "totally a binary".getBytes(StandardCharsets.UTF_8);

        serve("/yt-dlp_linux", binary);
        serve("/SHA2-256SUMS",
                ("0".repeat(64) + "  yt-dlp_linux\n").getBytes(StandardCharsets.UTF_8));

        Path target = tempDir.resolve("tools").resolve("yt-dlp");
        AtomicInteger reprobes = new AtomicInteger();

        provisioner(LINUX_X64).install(
                Map.of("ytdlp", target.toString()), reprobes::incrementAndGet);

        assertThat(target).doesNotExist();
        assertThat(tempDir.resolve("tools").resolve("yt-dlp.part")).doesNotExist();
        assertThat(reprobes.get()).isZero();

    }

    @Test
    void aMissingChecksumEntryRefusesTheInstall() {

        byte[] binary = "binary".getBytes(StandardCharsets.UTF_8);

        serve("/yt-dlp_linux", binary);
        serve("/SHA2-256SUMS", "some other asset only\n".getBytes(StandardCharsets.UTF_8));

        Path target = tempDir.resolve("tools").resolve("yt-dlp");

        provisioner(LINUX_X64).install(Map.of("ytdlp", target.toString()), () -> {
            throw new AssertionError("a refused install must not trigger a reprobe");
        });

        assertThat(target).doesNotExist();

    }

    @Test
    void anUnfetchableChecksumFileRefusesTheInstall() {

        // Only the binary is served: the sums route 404s, and a binary without a
        // verifiable hash must not land on disk.
        serve("/yt-dlp_linux", "binary".getBytes(StandardCharsets.UTF_8));

        Path target = tempDir.resolve("tools").resolve("yt-dlp");

        provisioner(LINUX_X64).install(Map.of("ytdlp", target.toString()), () -> {
            throw new AssertionError("a refused install must not trigger a reprobe");
        });

        assertThat(target).doesNotExist();
        assertThat(hits("/yt-dlp_linux")).isEqualTo(1);

    }

    @Test
    void ffmpegAndFfprobeComeOutOfOneTarXzArchive() throws IOException {

        byte[] ffmpeg = "ffmpeg-binary".getBytes(StandardCharsets.UTF_8);
        byte[] ffprobe = "ffprobe-binary".getBytes(StandardCharsets.UTF_8);
        byte[] licence = "GPL v3 ...".getBytes(StandardCharsets.UTF_8);

        byte[] archive = tarXz(Map.of(
                "ffmpeg-master-latest-linux64-gpl/bin/ffmpeg", ffmpeg,
                "ffmpeg-master-latest-linux64-gpl/bin/ffprobe", ffprobe,
                "ffmpeg-master-latest-linux64-gpl/doc/gpl.txt", licence));

        String asset = "ffmpeg-master-latest-linux64-gpl.tar.xz";

        serve("/" + asset, archive);
        serve("/checksums.sha256", sums(asset, archive).getBytes(StandardCharsets.UTF_8));

        Path tools = tempDir.resolve("tools");
        AtomicInteger reprobes = new AtomicInteger();

        provisioner(LINUX_X64).install(Map.of(
                "ffmpeg", tools.resolve("ffmpeg").toString(),
                "ffprobe", tools.resolve("ffprobe").toString()), reprobes::incrementAndGet);

        assertThat(Files.readAllBytes(tools.resolve("ffmpeg"))).isEqualTo(ffmpeg);
        assertThat(Files.readAllBytes(tools.resolve("ffprobe"))).isEqualTo(ffprobe);
        assertThat(Files.isExecutable(tools.resolve("ffmpeg"))).isTrue();
        // The archive carries docs and licence text: none of it belongs in tools/
        assertThat(tools.resolve("doc")).doesNotExist();
        assertThat(tools.resolve("ffmpeg-archive.part")).doesNotExist();
        assertThat(reprobes.get()).isEqualTo(1);

    }

    @Test
    void windowsFfmpegComesOutOfTheZipArchive() throws IOException {

        byte[] ffmpeg = "ffmpeg.exe-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] ffprobe = "ffprobe.exe-bytes".getBytes(StandardCharsets.UTF_8);

        byte[] archive = zip(Map.of(
                "ffmpeg-master-latest-win64-gpl/bin/ffmpeg.exe", ffmpeg,
                "ffmpeg-master-latest-win64-gpl/bin/ffprobe.exe", ffprobe,
                "ffmpeg-master-latest-win64-gpl/README.txt", "readme".getBytes(StandardCharsets.UTF_8)));

        String asset = "ffmpeg-master-latest-win64-gpl.zip";

        serve("/" + asset, archive);
        serve("/checksums.sha256", sums(asset, archive).getBytes(StandardCharsets.UTF_8));

        Path tools = tempDir.resolve("tools");

        provisioner(WINDOWS_X64).install(Map.of(
                "ffmpeg", tools.resolve("ffmpeg.exe").toString(),
                "ffprobe", tools.resolve("ffprobe.exe").toString()), () -> { });

        assertThat(Files.readAllBytes(tools.resolve("ffmpeg.exe"))).isEqualTo(ffmpeg);
        assertThat(Files.readAllBytes(tools.resolve("ffprobe.exe"))).isEqualTo(ffprobe);
        assertThat(tools.resolve("README.txt")).doesNotExist();

    }

    @Test
    void anUnsupportedPlatformSkipsEveryDownload() {

        Path tools = tempDir.resolve("tools");

        provisioner(new Platform(Os.UNSUPPORTED, Arch.X64)).install(Map.of(
                "ytdlp", tools.resolve("yt-dlp").toString(),
                "gallerydl", tools.resolve("gallery-dl").toString(),
                "ffmpeg", tools.resolve("ffmpeg").toString(),
                "ffprobe", tools.resolve("ffprobe").toString()), () -> {
            throw new AssertionError("a skipped install must not trigger a reprobe");
        });

        assertThat(hits("/yt-dlp_linux")).isZero();
        assertThat(tools).isEmptyDirectory();

    }

    @Test
    void galleryDlIsDownloadedFromItsReleaseAsset() throws IOException {

        byte[] binary = "gallery-dl-standalone".getBytes(StandardCharsets.UTF_8);

        serve("/gallery-dl.bin", binary);
        serve("/SHA256SUMS", sums("gallery-dl.bin", binary).getBytes(StandardCharsets.UTF_8));

        Path target = tempDir.resolve("tools").resolve("gallery-dl");

        provisioner(LINUX_X64).install(Map.of("gallerydl", target.toString()), () -> { });

        assertThat(Files.readAllBytes(target)).isEqualTo(binary);
        assertThat(Files.isExecutable(target)).isTrue();

    }

    @Test
    void anAlreadyExecutableTargetSkipsTheDownload() throws IOException {

        Path tools = tempDir.resolve("tools");
        Files.createDirectories(tools);

        Path target = tools.resolve("yt-dlp");
        Files.write(target, "already here".getBytes(StandardCharsets.UTF_8));
        assertThat(target.toFile().setExecutable(true, true)).isTrue();

        AtomicInteger reprobes = new AtomicInteger();

        provisioner(LINUX_X64).install(
                Map.of("ytdlp", target.toString()), reprobes::incrementAndGet);

        assertThat(hits("/yt-dlp_linux")).isZero();
        assertThat(reprobes.get()).isEqualTo(1);
        assertThat(Files.readString(target)).isEqualTo("already here");

    }

    @Test
    void redirectFlipsALandedToolToPresentWithoutARestart() throws IOException {

        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"),
                "the fake tool is a POSIX shell script");

        Path fake = tempDir.resolve("fake-ytdlp");
        Files.write(fake, "#!/bin/sh\necho 2026.99.1\n".getBytes(StandardCharsets.UTF_8));
        assertThat(fake.toFile().setExecutable(true, true)).isTrue();

        Toolchain chain = Toolchain.probe("definitely-missing-ytdlp", "missing-gallerydl",
                "missing-ffmpeg", "missing-ffprobe");

        assertThat(chain.hasYtDlp()).isFalse();

        ToolProvisioner provisioner = provisioner(LINUX_X64);
        Map<String, String> plan = provisioner.plan(chain);

        assertThat(plan).containsKeys("ytdlp", "gallerydl", "ffmpeg", "ffprobe");

        // The download "lands": the chain is re-pointed at the file that now exists.
        chain.redirect(Map.of("ytdlp", fake.toString()));

        assertThat(chain.hasYtDlp()).isTrue();
        assertThat(chain.ytDlp().version()).isEqualTo("2026.99.1");

    }

    @Test
    void awaitRunReturnsImmediatelyWhenNoRunWasStarted() {

        ToolProvisioner provisioner = provisioner(LINUX_X64);

        assertThat(provisioner.runActive()).isFalse();
        assertThat(provisioner.awaitRun(Duration.ofMillis(50))).isTrue();

    }

    @Test
    void awaitRunBlocksWhileTheRunIsStuckAndObservesItEnd() throws Exception {

        CountDownLatch inDownload = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        AppConfig.Downloader.Tools tools = new AppConfig.Downloader.Tools(
                "yt-dlp", "gallery-dl", "ffmpeg", "ffprobe", Duration.ZERO, true,
                tempDir.resolve("tools").toString());

        ToolProvisioner provisioner = new ToolProvisioner(tools, new MetricsRegistry(),
                new BlockingUrls(inDownload, release), LINUX_X64);

        provisioner.installAsync(Map.of("ytdlp", provisioner.targetPath("ytdlp")), () -> { });

        assertThat(inDownload.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(provisioner.runActive()).isTrue();

        // A wedged download must not park the caller forever: the bounded wait lapses
        // while the run is still in flight...
        assertThat(provisioner.awaitRun(Duration.ofMillis(200))).isFalse();
        assertThat(provisioner.runActive()).isTrue();

        release.countDown();

        // ...and once the run ends the same call observes it promptly.
        assertThat(provisioner.awaitRun(Duration.ofSeconds(10))).isTrue();
        assertThat(provisioner.runActive()).isFalse();

    }

    /** ReleaseUrls whose yt-dlp download parks on a latch: a run we can hold mid-flight. */
    private record BlockingUrls(CountDownLatch inDownload, CountDownLatch release)
            implements ToolProvisioner.ReleaseUrls {

        @Override
        public URI ytDlpBinary(String asset) {

            inDownload.countDown();

            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return null;   // unresolved asset = an honest skip, the run ends

        }

        @Override
        public URI ytDlpChecksums() {
            return null;
        }

        @Override
        public URI ffmpegArchive(String asset) {
            return null;
        }

        @Override
        public URI ffmpegChecksums() {
            return null;
        }

        @Override
        public URI galleryDlBinary(String asset) {
            return null;
        }

        @Override
        public URI galleryDlChecksums() {
            return null;
        }

    }

    private static byte[] tarXz(Map<String, byte[]> entries) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Map<String, byte[]> ordered = new LinkedHashMap<>(entries);

        try (XZCompressorOutputStream xz = new XZCompressorOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {

            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {

                TarArchiveEntry archiveEntry = new TarArchiveEntry(entry.getKey());
                archiveEntry.setSize(entry.getValue().length);
                archiveEntry.setMode(0755);

                tar.putArchiveEntry(archiveEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();

            }

            tar.finish();

        }

        return out.toByteArray();

    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(out)) {

            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {

                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();

            }

        }

        return out.toByteArray();

    }

}
