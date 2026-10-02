package eu.neydev.saver.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing rules of the local secret files, including the precedence between them. */
class DotEnvTest {

    @TempDir
    Path tempDir;

    private Path file(String name, String... lines) throws IOException {

        Path path = tempDir.resolve(name);
        Files.write(path, List.of(lines));

        return path;

    }

    @Test
    void commentsBlankLinesAndQuotesAreHandled() throws IOException {

        Path env = file(".env",
                "# a comment",
                "",
                "PLAIN=value",
                "QUOTED=\"with spaces\"",
                "SINGLE='single'",
                "  SPACED  =  trimmed  ");

        Map<String, String> values = DotEnv.loadFrom(List.of(env));

        assertThat(values)
                .containsEntry("PLAIN", "value")
                .containsEntry("QUOTED", "with spaces")
                .containsEntry("SINGLE", "single")
                .containsEntry("SPACED", "trimmed");

    }

    @Test
    void theSpecificFileWinsOverTheGenericOne() throws IOException {

        Path generic = file(".env", "TOKEN=generic", "ONLY_GENERIC=1");
        Path specific = file("config.env", "TOKEN=specific", "ONLY_SPECIFIC=2");

        // loadFrom applies candidates in order, later wins - the production order puts
        // config/.env last, exactly like this call.
        Map<String, String> values = DotEnv.loadFrom(List.of(generic, specific));

        assertThat(values)
                .containsEntry("TOKEN", "specific")
                .containsEntry("ONLY_GENERIC", "1")
                .containsEntry("ONLY_SPECIFIC", "2");

    }

    @Test
    void anUnreadableFileIsSkippedNotFatal() {
        Map<String, String> values = DotEnv.loadFrom(List.of(tempDir.resolve("absent")));
        assertThat(values).isEmpty();
    }

}

