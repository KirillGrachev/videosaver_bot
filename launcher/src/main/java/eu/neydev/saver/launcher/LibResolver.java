package eu.neydev.saver.launcher;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Library resolver (Java 8): reads the manifest {@code relpath|sha256}
 * (Maven Central repository layout), checks the local {@code libs/}
 * and downloads the missing or corrupted ones. Writing is atomic: temp + move,
 * a half-downloaded jar cannot get into the classpath.
 */
public final class LibResolver {

    private final Path libsDir;
    private final String centralBase;

    public LibResolver(Path libsDir, String centralBase) {
        this.libsDir = libsDir;
        this.centralBase = centralBase.endsWith("/") ? centralBase : centralBase + "/";
    }

    /** One manifest line: a path in the repository layout + sha256. */
    public static final class Entry {

        private final String relPath;
        private final String sha256;

        public Entry(String relPath, String sha256) {

            this.relPath = relPath;
            this.sha256 = sha256;

        }

        public String relPath() {
            return relPath;
        }

        public String sha256() {
            return sha256;
        }

    }

    public static List<Entry> parseManifest(InputStream in) throws IOException {

        List<Entry> entries = new ArrayList<Entry>();
        String content = new String(readAll(in), StandardCharsets.UTF_8);
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int sep = line.lastIndexOf('|');
            if (sep > 0) {
                entries.add(new Entry(line.substring(0, sep), line.substring(sep + 1)));
            }
        }

        return entries;

    }

    public List<Path> resolve(List<Entry> manifest) throws IOException {

        List<Path> jars = new ArrayList<Path>();
        for (Entry entry : manifest) {
            Path target = libsDir.resolve(entry.relPath());
            if (!Files.isRegularFile(target) || !sha256(target).equalsIgnoreCase(entry.sha256())) {
                download(entry, target);
            }

            jars.add(target);
        }

        return jars;

    }

    private void download(Entry entry, Path target) throws IOException {

        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");

        HttpURLConnection connection =
                (HttpURLConnection) new URL(centralBase + entry.relPath()).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "saver-bot-launcher");

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to download " + entry.relPath() + ": HTTP " + status);
        }

        InputStream in = connection.getInputStream();
        OutputStream out = new FileOutputStream(temp.toFile());
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {

            in.close();
            out.close();

        }

        String actual = sha256(temp);
        if (!actual.equalsIgnoreCase(entry.sha256())) {
            Files.deleteIfExists(temp);
            throw new IOException("Checksum mismatch for " + entry.relPath()
                    + ": expected " + entry.sha256() + ", actual " + actual);
        }

        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = Files.newInputStream(file);
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            return toHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {

        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }

        return sb.toString();

    }

    private static byte[] readAll(InputStream in) throws IOException {

        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }

        in.close();
        return buffer.toByteArray();

    }

}

