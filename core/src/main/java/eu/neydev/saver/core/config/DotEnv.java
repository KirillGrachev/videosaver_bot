package eu.neydev.saver.core.config;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A local convenience for secrets: {@code .env} / {@code config/.env} with lines
 * {@code KEY=value} is picked up as a fallback to environment variables.
 * The files are added to .gitignore - secrets do not reach git, but a local run
 * stops requiring exports in every terminal.
 *
 * <p>Precedence: an environment variable wins over both files, and between the files
 * {@code config/.env} wins over {@code .env} - the more specific location must not be
 * silently overridden by the generic one.
 */
public final class DotEnv {

    /** Later files override earlier ones, so the specific config/.env comes last. */
    private static final List<Path> DEFAULT_FILES =
            List.of(Path.of(".env"), Path.of("config/.env"));

    private static final Map<String, String> VALUES = loadFrom(DEFAULT_FILES);

    private DotEnv() {
    }

    public static @Nullable String get(String key) {
        return VALUES.get(key);
    }

    /** Testable: the candidate files are explicit instead of the working directory. */
    static Map<String, String> loadFrom(List<Path> candidates) {

        Map<String, String> result = new HashMap<>();

        for (Path candidate : candidates) {

            if (!Files.isReadable(candidate)) {
                continue;
            }

            try {

                for (String line : Files.readAllLines(candidate)) {

                    String trimmed = line.trim();

                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }

                    int eq = trimmed.indexOf('=');

                    if (eq > 0) {
                        result.put(trimmed.substring(0, eq).trim(),
                                unquote(trimmed.substring(eq + 1).trim()));
                    }

                }

            } catch (IOException ignored) {}
        }

        return Map.copyOf(result);

    }

    private static String unquote(String value) {

        if (value.length() > 1
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }

        return value;

    }

}

