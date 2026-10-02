package eu.neydev.saver.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard rail against leaking real infrastructure into the sources: a production
 * proxy host, a home address or a server IP copied into a test or a config comment
 * ends up in git forever. Only loopback, private and RFC 5737 documentation ranges
 * (192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24) may appear in the sources.
 */
final class SourceHygieneTest {

    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    private static final List<String> SCANNED_EXTENSIONS =
            List.of(".java", ".yml", ".yaml", ".xml", ".properties", ".sql", ".bat", ".sh");

    @Test
    void sourcesContainNoRealIpAddresses() throws IOException {

        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(SourceHygieneTest::isScanned)
                    .toList()) {

                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = IPV4.matcher(lines.get(i));
                    while (matcher.find()) {
                        int first = Integer.parseInt(matcher.group(1));
                        int second = Integer.parseInt(matcher.group(2));
                        if (!allowed(first, second)) {
                            violations.add(root.relativize(file) + ":" + (i + 1) + " -> " + matcher.group());
                        }
                    }
                }

            }
        }

        assertThat(violations)
                .as("real IP addresses in sources (use 203.0.113.x / 198.51.100.x / 192.0.2.x for examples)")
                .isEmpty();

    }

    private static boolean isScanned(Path file) {

        String name = file.toString().replace('\\', '/');
        if (!name.contains("/src/") || name.contains("/target/")) {
            return false;
        }

        return SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith);

    }

    private static boolean allowed(int first, int second) {

        if (first == 127 || first == 10 || first == 0) {
            return true;
        }

        if (first == 192 && (second == 168 || second == 0)) {
            return true;
        }

        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }

        if (first == 169 && second == 254) {
            return true;
        }

        return (first == 192 && second == 0) || (first == 198 && second == 51) || (first == 203 && second == 0);

    }

    private static Path repositoryRoot() {

        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("core")) && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }

            dir = dir.getParent();
        }

        throw new IllegalStateException("repository root not found");

    }

}

