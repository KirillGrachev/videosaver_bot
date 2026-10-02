package eu.neydev.saver.launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * JRE self-provisioning: if the bot is started on Java below 21 (or by a start script
 * without Java at all), downloads a Temurin 21 JRE into {@code runtime/} next to the launcher
 * and restarts the process with it. Without admin rights or manual installation.
 *
 * <p>The class is deliberately Java 8 compatible: this is the single point that
 * must start on any machine, to be able to fix everything afterwards.
 */
public final class RuntimeProvisioner {

    private static final int REQUIRED_FEATURE = 21;
    private static final String ADOPTIUM_ASSETS =
            "https://api.adoptium.net/v3/assets/latest/21/hotspot?image_type=jre&os=";

    private RuntimeProvisioner() {
    }

    /**
     * @return {@code true} if the current process is already suitable (Java 21+);
     *         {@code false} after a restart in a new JVM (the calls do not return).
     */
    public static boolean ensureOrReexec(String[] args) throws IOException, InterruptedException {

        int feature = currentFeatureVersion();
        if (feature >= REQUIRED_FEATURE) {
            return true;
        }

        System.out.println("[launcher] found Java " + feature + ", need " + REQUIRED_FEATURE
                + "+ - downloading a bundled JRE");
        File javaExe = provision();
        reexec(javaExe, args);

        return false;

    }

    public static int currentFeatureVersion() {
        return featureOfClassVersion(System.getProperty("java.class.version", "52.0"));
    }

    /** {@code 52.0} -> 8, {@code 65.0} -> 21: for tests and diagnostics. */
    public static int featureOfClassVersion(String classVersion) {

        int major;
        try {
            major = (int) Float.parseFloat(classVersion.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 8;
        }

        return major - 44;

    }

    /** The runtime/ directory next to the launcher jar (overridable by a property). */
    public static File runtimeDir(File launcherJarDir) {
        String override = System.getProperty("saver.runtime.dir");
        return override != null ? new File(override) : new File(launcherJarDir, "runtime");
    }

    /** An already downloaded and extracted JRE or {@code null}. */
    public static File findProvisioned(File runtimeDir) {

        File marker = new File(runtimeDir, "java.txt");
        if (marker.isFile()) {
            File java = new File(readFirstLine(marker));
            if (java.isFile()) {
                return java;
            }
        }

        return null;

    }

    private static File provision() throws IOException {

        File launcherDir = Launcher.jarDirectory();
        File runtimeDir = runtimeDir(launcherDir);
        File existing = findProvisioned(runtimeDir);
        if (existing != null) {
            return existing;
        }

        String os = osName();
        String arch = archName();
        boolean zip = "windows".equals(os);
        File archive = new File(runtimeDir, zip ? "jre21.zip" : "jre21.tar.gz");

        runtimeDir.mkdirs();
        System.out.println("[launcher] downloading Temurin 21 JRE (" + os + "/" + arch + ")...");
        String[] asset = parseAssetJson(fetchText(ADOPTIUM_ASSETS + os + "&architecture=" + arch));
        downloadWithFallback(asset[0], archive);
        System.out.println("[launcher] downloaded " + archive.length() + " bytes ("
                + String.format(Locale.ROOT, "%.1f", archive.length() / (1024.0 * 1024.0))
                + " MB)");

        String actual = sha256Hex(archive);
        if (!actual.equalsIgnoreCase(asset[1])) {
            archive.delete();
            throw new IOException("JRE checksum mismatch: expected "
                    + asset[1] + ", actual " + actual);
        }

        System.out.println("[launcher] extracting to " + runtimeDir.getAbsolutePath());
        extract(archive, runtimeDir, zip);
        archive.delete();

        File java = locateJava(runtimeDir, zip);
        writeMarker(runtimeDir, java);
        System.out.println("[launcher] JRE ready: " + java.getAbsolutePath());

        return java;

    }

    /**
     * Download with fallback: old JREs (your Java 8 and similar) have an outdated
     * cacerts and cannot verify modern TLS chains (PKIX path building
     * failed). In that case download with a system downloader - PowerShell/curl
     * use the OS's up-to-date certificate store.
     */
    private static void downloadWithFallback(String url, File target) throws IOException {

        try {
            downloadJava(url, target);
            return;
        } catch (javax.net.ssl.SSLHandshakeException e) {
            System.out.println("[launcher] JVM trust store is outdated - "
                    + "falling back to system downloader (PowerShell/curl)");
        }

        externalDownload(url, target);

    }

    private static void downloadJava(String url, File target) throws IOException {

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "saver-bot-launcher");

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to download JRE: HTTP " + status + " (" + url + ")");
        }

        File part = new File(target.getParentFile(), target.getName() + ".part");
        InputStream in = connection.getInputStream();
        FileOutputStream out = new FileOutputStream(part);

        try {

            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;

            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                if (total % (8 * 1024 * 1024) < buffer.length) {
                    System.out.println("[launcher]   ..." + (total / 1024 / 1024) + " MB");
                }
            }

        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            in.close();
            out.close();
        }

        java.nio.file.Files.move(part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    }

    private static void externalDownload(String url, File target) throws IOException {

        target.delete();
        ProcessBuilder builder = new ProcessBuilder(externalDownloadCommand(osName(), url, target));
        builder.redirectErrorStream(true);
        Process process = builder.start();

        try {
            if (process.waitFor() != 0 || !target.isFile() || target.length() == 0) {
                throw new IOException("System downloader failed to fetch " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }

    }

    static List<String> externalDownloadCommand(String os, String url, File target) {

        if ("windows".equals(os)) {
            return Arrays.asList("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-Command", "$ErrorActionPreference='Stop'; "
                            + "[Net.ServicePointManager]::SecurityProtocol = "
                            + "[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '"
                            + url + "' -OutFile '" + target.getAbsolutePath() + "'");
        }

        return Arrays.asList("curl", "-fsSL", "--retry", "3", "-o", target.getAbsolutePath(), url);

    }

    private static String fetchText(String url) throws IOException {

        File temp = File.createTempFile("adoptium", ".json");

        try {

            downloadWithFallback(url, temp);
            byte[] bytes = java.nio.file.Files.readAllBytes(temp.toPath());

            return new String(bytes, "UTF-8");

        } finally {
            temp.delete();
        }

    }

    /**
     * Parsing the Adoptium assets API response: {@code [link, checksum]}.
     * Regex instead of a JSON parser: the launcher has no dependencies.
     */
    static String[] parseAssetJson(String json) throws IOException {

        // In the Adoptium response a binary has both an installer (.msi) and a package (.zip/.tar.gz):
        // take strictly the "package" block, otherwise the installer is downloaded instead of the archive.
        java.util.regex.Matcher pkg = java.util.regex.Pattern
                .compile("\"package\"\\s*:\\s*\\{(.*?)\\}",
                        java.util.regex.Pattern.DOTALL).matcher(json);
        String block = pkg.find() ? pkg.group(1) : json;

        java.util.regex.Matcher link = java.util.regex.Pattern
                .compile("\"link\"\\s*:\\s*\"(https://[^\"]+)\"").matcher(block);
        java.util.regex.Matcher sum = java.util.regex.Pattern
                .compile("\"checksum\"\\s*:\\s*\"([0-9a-fA-F]{64})\"").matcher(block);
        if (!link.find() || !sum.find()) {
            throw new IOException("Failed to parse Adoptium API response");
        }

        return new String[]{link.group(1), sum.group(1)};

    }

    private static String sha256Hex(File file) throws IOException {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = new java.io.FileInputStream(file);

            try {

                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }

            } finally {
                in.close();
            }

            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }

            return sb.toString();

        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void extract(File archive, File runtimeDir, boolean zip) throws IOException {

        ProcessBuilder builder;

        if (zip) {
            builder = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Expand-Archive -LiteralPath '" + archive.getAbsolutePath()
                            + "' -DestinationPath '" + runtimeDir.getAbsolutePath() + "' -Force");
        } else {
            builder = new ProcessBuilder("tar", "-xzf", archive.getAbsolutePath(),
                    "-C", runtimeDir.getAbsolutePath());
        }

        builder.inheritIO();
        Process process = builder.start();

        try {
            if (process.waitFor() != 0) {
                throw new IOException("JRE extraction failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted", e);
        }

    }

    private static File locateJava(File runtimeDir, boolean zip) throws IOException {

        File[] dirs = runtimeDir.listFiles();
        if (dirs == null) {
            throw new IOException("runtime/ is empty after extraction");
        }

        for (File dir : dirs) {

            if (!dir.isDirectory()) {
                continue;
            }

            File candidate = zip
                    ? new File(dir, "bin\\java.exe")
                    : new File(dir, "bin/java");

            if (candidate.isFile()) {
                return candidate;
            }

        }

        throw new IOException("bin/java not found after extraction in " + runtimeDir);

    }

    private static void writeMarker(File runtimeDir, File java) throws IOException {
        FileOutputStream out = new FileOutputStream(new File(runtimeDir, "java.txt"));
        try {
            out.write(java.getAbsolutePath().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String readFirstLine(File file) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, "UTF-8").trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void reexec(File javaExe, String[] args) throws IOException, InterruptedException {

        File launcherJar = new File(Launcher.jarDirectory(), "saver-launcher.jar");

        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add(javaExe.getAbsolutePath());
        command.add("-XX:MaxRAMPercentage=75");
        command.addAll(forwardedEncodingOptions());
        command.add("-jar");
        command.add(launcherJar.getAbsolutePath());

        for (String arg : args) {
            command.add(arg);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        builder.environment().put("SAVER_RUNTIME", javaExe.getAbsolutePath());
        Process process = builder.start();
        System.exit(process.waitFor());

    }

    /**
     * The encoding flags of {@link ConsoleEncoding} must survive the runtime change:
     * otherwise the child JVM sees CP866 again and goes into an extra restart.
     */
    static List<String> forwardedEncodingOptions() {

        List<String> options = new java.util.ArrayList<String>();
        if ("1".equals(System.getProperty(ConsoleEncoding.MARKER))) {
            options.addAll(ConsoleEncoding.jvmOptions());
        }

        return options;

    }

    private static String osName() {

        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }

        if (name.contains("mac") || name.contains("darwin")) {
            return "mac";
        }

        return "linux";

    }

    private static String archName() {

        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }

        return "x64";

    }

}

