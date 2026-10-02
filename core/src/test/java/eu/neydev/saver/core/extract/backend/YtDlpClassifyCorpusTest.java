package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.util.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anti-circularity net for error classification: a golden corpus of REAL yt-dlp
 * error lines (resource {@code ytdlp-error-corpus.txt}, collected from releases
 * 2023-2026) drives the classifier. When a yt-dlp update rewords an error, the new
 * line goes into the corpus FIRST and the pattern table must follow - the corpus is
 * the contract, the patterns are an implementation.
 */
class YtDlpClassifyCorpusTest {

    private static final YtDlpExtractor EXTRACTOR = new YtDlpExtractor(
            Toolchain.probe("missing-a", "missing-b", "missing-c"),
            YtDlpExtractor.Options.defaults(), new MetricsRegistry());

    @Test
    void everyCorpusLineClassifiesAsDocumented() throws IOException {

        List<String> lines;

        try (InputStream in = YtDlpClassifyCorpusTest.class.getClassLoader()
                .getResourceAsStream("ytdlp-error-corpus.txt")) {

            assertThat(in).as("corpus resource").isNotNull();
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();

        }

        int checked = 0;

        for (String line : lines) {

            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            int tab = line.indexOf('\t');
            assertThat(tab).as("corpus line format: %s", line).isPositive();

            Category expected = Category.valueOf(line.substring(0, tab).trim());
            String errorLine = line.substring(tab + 1);

            ProcessRunner.Result result = new ProcessRunner.Result(
                    1, false, false, List.of(), List.of(errorLine));

            ExtractionException classified = EXTRACTOR.classify(result, "https://example.test/v");

            assertThat(classified.category())
                    .as("classification of: %s", errorLine)
                    .isEqualTo(expected);

            checked++;

        }

        // A corpus that silently empties is a broken test, not a green one.
        assertThat(checked).isGreaterThanOrEqualTo(15);

    }

}
