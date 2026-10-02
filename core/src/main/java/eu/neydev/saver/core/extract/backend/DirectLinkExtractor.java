package eu.neydev.saver.core.extract.backend;

import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionException;
import eu.neydev.saver.core.extract.ExtractionException.Category;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.Extractor;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.media.MimeTypes;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Direct-link backend: the URL already IS the file (CDN attachments, i.imgur.com/xyz.jpg,
 * media.tenor.com/..., a webhook-pasted video link). No parsing, no tools: a capped
 * streaming download, kind from the Content-Type when present and from the extension
 * otherwise. An HTML answer means "this was a page after all" - UNSUPPORTED, so the
 * chain hands the URL to the scraper instead of saving a .html "video".
 */
public final class DirectLinkExtractor implements Extractor {

    public static final String BACKEND_ID = "direct";

    private final SafeHttp http;

    public DirectLinkExtractor(SafeHttp http) {
        this.http = http;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) throws ExtractionException {

        SafeHttp.Response response = http.fetch(request.url(), 4096, BACKEND_ID);

        String contentType = response.contentType() == null
                ? ""
                : response.contentType().toLowerCase(Locale.ROOT);

        if (response.isHtml()) {
            throw new ExtractionException(Category.UNSUPPORTED, BACKEND_ID,
                    "URL is an HTML page, not a direct file");
        }

        // fetch() already pulled up to 4 KB; for small files that IS the download.
        String fileName = GenericHttpExtractor.fileNameFrom(
                request.url(), 0, contentType.isEmpty() ? null : contentType);
        Path destination = GenericHttpExtractor.uniquePath(request.workDir(), fileName);

        long bytes;

        if (response.body().length < 4096) {

            try {
                java.nio.file.Files.write(destination, response.body());
            } catch (java.io.IOException e) {
                throw new ExtractionException(Category.NETWORK, BACKEND_ID,
                        "Cannot write file: " + e.getMessage(), e);
            }

            bytes = response.body().length;

        } else {
            bytes = http.download(request.url(), destination, request.maxFileBytes(), BACKEND_ID);
        }

        MediaKind kind = kindFrom(contentType, fileName);

        ExtractedItem item = new ExtractedItem(kind, destination, bytes, null,
                null, null, null, request.url().toString());

        return new ExtractionResult(request.source(), BACKEND_ID, java.util.List.of(item),
                null, null, request.url().toString(), false);

    }

    private static MediaKind kindFrom(String contentType, String fileName) {

        if (contentType.startsWith("image/gif")) {
            return MediaKind.GIF;
        }

        if (contentType.startsWith("image/")) {
            return MediaKind.PHOTO;
        }

        if (contentType.startsWith("video/")) {
            return MediaKind.VIDEO;
        }

        if (contentType.startsWith("audio/")) {
            return MediaKind.AUDIO;
        }

        return MediaKind.fromExtension(fileName.contains(".")
                ? fileName
                : fileName + "." + MimeTypes.extensionFor(contentType));

    }

}
