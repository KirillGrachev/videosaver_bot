package eu.neydev.saver.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The distribution entry point, Java 8 compatible: the main jar contains only
 * the project's classes, third-party ones live in {@code libs/} and are downloaded by the manifest
 * with sha256. If the current JVM is older than 21 - {@link RuntimeProvisioner} downloads
 * Temurin 21 JRE into {@code runtime/} and restarts the process with it.
 *
 * <p>System properties: {@code saver.home}, {@code saver.libs.dir},
 * {@code saver.central.base}, {@code saver.app.jar},
 * {@code saver.manifest}, {@code saver.runtime.dir}.
 */
public final class Launcher {

    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";

    public static void main(String[] args) throws Exception {

        if (!RuntimeProvisioner.ensureOrReexec(args)) {
            return;
        }

        if (!ConsoleEncoding.ensureUtf8(args)) {
            return;
        }

        File home = jarDirectory();
        Path libsDir = Paths.get(System.getProperty("saver.libs.dir",
                new File(home, "libs").getAbsolutePath()));
        String centralBase = System.getProperty("saver.central.base", CENTRAL);
        Path appJar = Paths.get(System.getProperty("saver.app.jar",
                new File(home, "saver-bot.jar").getAbsolutePath()));

        List<LibResolver.Entry> manifest = readManifest(home, libsDir);
        System.out.println("[launcher] manifest: libs=" + manifest.size()
                + ", dir=" + libsDir);
        LibResolver resolver = new LibResolver(libsDir, centralBase);
        List<Path> jars = resolver.resolve(manifest);

        List<URL> urls = new ArrayList<URL>();
        if (!Files.isRegularFile(appJar)) {
            throw new IllegalStateException("Application jar not found: " + appJar);
        }

        urls.add(appJar.toUri().toURL());
        for (Path jar : jars) {
            urls.add(jar.toUri().toURL());
        }

        URLClassLoader classLoader = new URLClassLoader(
                urls.toArray(new URL[0]), platformClassLoader());
        Thread.currentThread().setContextClassLoader(classLoader);

        Class<?> mainClass = Class.forName("eu.neydev.saver.app.Main", true, classLoader);
        mainClass.getMethod("main", String[].class).invoke(null, (Object) args);

    }

    /**
     * Parent classloader: platform (Java 9+) isolates the application from
     * the system classpath; on Java 8 the fallback is bootstrap (null).
     */
    private static ClassLoader platformClassLoader() {
        try {
            return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader")
                    .invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The directory containing the launcher jar itself. */
    public static File jarDirectory() {

        String override = System.getProperty("saver.home");
        if (override != null) {
            return new File(override);
        }

        try {
            File jar = new File(Launcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return jar.getParentFile() == null ? new File(".") : jar.getParentFile();
        } catch (Exception e) {
            return new File(".");
        }

    }

    private static List<LibResolver.Entry> readManifest(File home, Path libsDir) throws IOException {

        Path manifestFile = Paths.get(System.getProperty("saver.manifest",
                new File(home, "libs-manifest.txt").getAbsolutePath()));
        if (Files.isReadable(manifestFile)) {
            return LibResolver.parseManifest(Files.newInputStream(manifestFile));
        }

        Path fallback = libsDir.resolve("libs-manifest.txt");
        if (Files.isReadable(fallback)) {
            return LibResolver.parseManifest(Files.newInputStream(fallback));
        }

        InputStream in = Launcher.class.getClassLoader().getResourceAsStream("libs-manifest.txt");
        return in == null ? new ArrayList<LibResolver.Entry>() : LibResolver.parseManifest(in);

    }

    /** Sorted list of jars in the directory (for the manifest and tests). */
    public static List<Path> listJars(Path dir) throws IOException {

        if (!Files.isDirectory(dir)) {
            return new ArrayList<Path>();
        }

        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> jars = new ArrayList<Path>();
            stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(jars::add);
            return jars;
        }

    }

    static URL url(String spec) throws MalformedURLException {
        return new URL(spec);
    }

}

