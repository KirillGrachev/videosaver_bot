package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.ProgressListener;
import eu.neydev.saver.core.extract.QualityPreset;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceBackend;
import eu.neydev.saver.core.source.SourceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gallery-dl contract against a fake tool: files land in nested folders (site/
 * gallery layout), the N+1 range trick makes "truncated" a FACT instead of a guess,
 * and the overflow file is deleted instead of delivered.
 */
class GalleryDlExtractorTest {

    private static final Source SOURCE = new Source("fakeart", "FakeArt",
            List.of("fakeart.example"), SourceBackend.GALLERYDL, SourceStatus.OK);

    /**
     * @param files how many files the fake "downloads"; nested one level like the real tool.
     */
    private static Path fakeGalleryDl(Path dir, int files, String mode) throws IOException {

        StringBuilder script = new StringBuilder("""
                #!/bin/sh
                for a in "$@"; do
                  if [ "$a" = "-version" ] || [ "$a" = "--version" ]; then
                    echo "gallery-dl 9.9.9 (fake)"; exit 0
                  fi
                done
                DEST="."
                prev=""
                for a in "$@"; do
                  if [ "$prev" = "--directory" ]; then DEST="$a"; fi
                  prev="$a"
                done
                # Arg dump for assertions. It lives NEXT TO the work dir, not inside
                # it: the extractor's overflow logic and the file-count assertions
                # walk the work dir and must never see this bookkeeping file.
                ARGS="$(dirname "$DEST")/.cmd-args.txt"
                for a in "$@"; do printf '%s\\n' "$a" >> "$ARGS"; done
                """);

        if ("fail-auth".equals(mode)) {

            script.append("""
                    echo "[fakeart][error] Login required to access this gallery (Authorization)" >&2
                    exit 1
                    """);

        } else {

            script.append("mkdir -p \"$DEST/fakeart/gallery\"\n");

            for (int i = 1; i <= files; i++) {
                script.append("printf 'img%02d' > \"$DEST/fakeart/gallery/%03d.png\"\n".formatted(i, i));
            }

            script.append("exit 0\n");

        }

        Path file = dir.resolve("gallery-dl-" + files + "-" + mode);
        Files.writeString(file, script.toString());
        file.toFile().setExecutable(true);

        return file;

    }

    private static GalleryDlExtractor extractor(Path fake) {

        Toolchain tools = Toolchain.probe("missing-yt", fake.toString(), "missing-ff");

        return new GalleryDlExtractor(tools, new GalleryDlExtractor.Options(
                null, null, Duration.ofSeconds(30)));

    }

    private static ExtractionRequest request(Path workDir, int maxItems) {

        return new ExtractionRequest(SOURCE, URI.create("https://fakeart.example/gallery/1"),
                QualityPreset.BEST, workDir, 100_000_000L, maxItems, ProgressListener.NOOP);

    }

    @Test
    void commandSpellsTheFilenameTemplateFlagTheWayGalleryDlAccepts(@TempDir Path dir)
            throws IOException {

        GalleryDlExtractor extractor = extractor(fakeGalleryDl(dir, 1, "ok"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        extractor.extract(request(workDir, 5));

        // Token-exact on purpose: the rejected spelling (--filename-format, not an
        // option in gallery-dl 1.32) STARTS WITH the good one, so a substring check
        // would happily pass on the flag that kills every job at argument parsing.
        // The fake dumps its args next to the work dir (the work dir itself is
        // file-counted by the overflow assertions).
        List<String> tokens = Files.readAllLines(dir.resolve(".cmd-args.txt"));

        assertThat(tokens).contains("--filename");
        assertThat(tokens).doesNotContain("--filename-format");

    }

    @Test
    void nestedFoldersAreScannedRecursively(@TempDir Path dir) throws IOException {

        GalleryDlExtractor extractor = extractor(fakeGalleryDl(dir, 3, "ok"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        ExtractionResult result = extractor.extract(request(workDir, 10));

        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).kind()).isEqualTo(MediaKind.PHOTO);
        assertThat(result.truncated()).isFalse();

    }

    @Test
    void theOverflowFileProvesTruncationAndIsDeleted(@TempDir Path dir) throws IOException {

        // The fake "downloads" 4 files while maxItems=3: with the N+1 range the tool
        // would have fetched exactly 4 - the 4th proves the gallery is bigger.
        GalleryDlExtractor extractor = extractor(fakeGalleryDl(dir, 4, "ok"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        ExtractionResult result = extractor.extract(request(workDir, 3));

        assertThat(result.items()).hasSize(3);
        assertThat(result.truncated()).isTrue();

        // The overflow file must not linger in the vault.
        try (var files = Files.walk(workDir)) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(3);
        }

    }

    @Test
    void exactlyMaxItemsIsNotTruncated(@TempDir Path dir) throws IOException {

        // 3 files with maxItems=3: the N+1 fetch found nothing more - an honest
        // "this is everything", unlike the old items==max heuristic.
        GalleryDlExtractor extractor = extractor(fakeGalleryDl(dir, 3, "ok"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        ExtractionResult result = extractor.extract(request(workDir, 3));

        assertThat(result.items()).hasSize(3);
        assertThat(result.truncated()).isFalse();

    }

    @Test
    void authFailuresAreClassified(@TempDir Path dir) throws IOException {

        GalleryDlExtractor extractor = extractor(fakeGalleryDl(dir, 0, "fail-auth"));
        Path workDir = Files.createDirectories(dir.resolve("job"));

        assertThatThrownBy(() -> extractor.extract(request(workDir, 10)))
                .isInstanceOf(ExtractionException.class)
                .extracting(e -> ((ExtractionException) e).category())
                .isEqualTo(Category.LOGIN_REQUIRED);

    }

}
