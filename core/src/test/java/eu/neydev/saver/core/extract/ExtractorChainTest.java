package eu.neydev.saver.core.extract;

import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceBackend;
import eu.neydev.saver.core.source.SourceCatalog;
import eu.neydev.saver.core.source.SourceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractorChainTest {

    private static final Source SOURCE = new Source("test", "Test", List.of("test.example"),
            SourceBackend.YTDLP, SourceStatus.OK);

    /** A scripted backend: fails with a canned error, or "downloads" a marker file. */
    private record Fake(String backendId, boolean available, Category failure, int order)
            implements Extractor {

        @Override
        public String backendId() {
            return backendId;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public ExtractionResult extract(ExtractionRequest request) {

            if (failure != null) {
                throw new ExtractionException(failure, backendId, failure.name());
            }

            try {

                Path file = request.workDir().resolve(backendId + "-file.mp4");
                java.nio.file.Files.writeString(file, "content of " + backendId);

                return new ExtractionResult(request.source(), backendId,
                        List.of(ExtractedItem.of(
                                eu.neydev.saver.core.media.MediaKind.VIDEO, file)),
                        "title", null, request.url().toString(), false);

            } catch (java.io.IOException e) {
                throw new ExtractionException(Category.UNKNOWN, backendId, e.getMessage(), e);
            }

        }

    }

    private static ExtractionRequest request(Path workDir) {
        return new ExtractionRequest(SOURCE, URI.create("https://test.example/v/1"),
                QualityPreset.BEST, workDir);
    }

    @Test
    void theSourceBackendRunsFirst(@TempDir Path dir) {

        ExtractorChain chain = new ExtractorChain(List.of(
                new Fake("http", true, null, 0),
                new Fake("ytdlp", true, null, 0),
                new Fake("direct", true, null, 0)));

        ExtractionResult result = chain.extract(request(dir));

        assertThat(result.backendId()).isEqualTo("ytdlp");

    }

    @Test
    void failureFallsThroughToTheNextBackend(@TempDir Path dir) {

        ExtractorChain chain = new ExtractorChain(List.of(
                new Fake("ytdlp", true, Category.NOT_FOUND, 0),
                new Fake("http", true, null, 0)));

        ExtractionResult result = chain.extract(request(dir));

        assertThat(result.backendId()).isEqualTo("http");

    }

    @Test
    void unavailableBackendsAreSkippedWithoutAFailure(@TempDir Path dir) {

        ExtractorChain chain = new ExtractorChain(List.of(
                new Fake("ytdlp", false, null, 0),
                new Fake("http", true, null, 0)));

        ExtractionResult result = chain.extract(request(dir));

        assertThat(result.backendId()).isEqualTo("http");
        assertThat(chain.availability()).containsEntry("ytdlp", false)
                .containsEntry("http", true);

    }

    @Test
    void theMostSpecificErrorSurvivesTotalFailure(@TempDir Path dir) {

        // http fails "not found", ytdlp fails "login required": the user must see
        // the actionable reason (login), not the generic one.
        ExtractorChain chain = new ExtractorChain(List.of(
                new Fake("ytdlp", true, Category.LOGIN_REQUIRED, 0),
                new Fake("http", true, Category.NOT_FOUND, 0),
                new Fake("direct", true, Category.UNKNOWN, 0)));

        assertThatThrownBy(() -> chain.extract(request(dir)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.LOGIN_REQUIRED);

    }

    @Test
    void toolMissingIsReportedWhenNothingElseExists(@TempDir Path dir) {

        ExtractorChain chain = new ExtractorChain(List.of(
                new Fake("ytdlp", false, null, 0),
                new Fake("gallerydl", false, null, 0),
                new Fake("direct", false, null, 0)));

        // "http" is not even registered, so the only failures are TOOL_MISSING ones.
        assertThatThrownBy(() -> chain.extract(request(dir)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.TOOL_MISSING);

    }

    @Test
    void toolMissingOutranksAScraperFindingNothing(@TempDir Path dir) {

        // The scraper's UNSUPPORTED only proves the page needs a real tool: on a server
        // without yt-dlp a video page with no exposed media file must be reported as
        // the operator problem it is, not as "this site is unsupported".
        ExtractorChain chain = new ExtractorChain(List.of(
                new Fake("ytdlp", false, null, 0),
                new Fake("http", true, Category.UNSUPPORTED, 0),
                new Fake("direct", true, Category.UNSUPPORTED, 0)));

        assertThatThrownBy(() -> chain.extract(request(dir)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.TOOL_MISSING);

    }

    @Test
    void gallerySourcesStartWithGalleryDl(@TempDir Path dir) {

        ExtractorChain chain = new ExtractorChain(List.of(
                new Fake("ytdlp", true, null, 0),
                new Fake("gallerydl", true, null, 0)));

        Source gallery = new Source("pixiv-test", "Pixiv Test", List.of("pixiv.example"),
                SourceBackend.GALLERYDL, SourceStatus.OK);

        ExtractionResult result = chain.extract(new ExtractionRequest(gallery,
                URI.create("https://pixiv.example/art/1"), QualityPreset.BEST, dir));

        assertThat(result.backendId()).isEqualTo("gallerydl");

    }

    @Test
    void genericSourceIsAlwaysAvailableAsFallback() {

        ExtractorChain chain = new ExtractorChain(List.of());

        // Even an unknown-host request produces a ladder (which then fails with
        // "no extraction attempted" - an honest UNKNOWN, never a crash).
        assertThat(chain.orderFor(SourceCatalog.GENERIC)).isNotEmpty();

    }

}
