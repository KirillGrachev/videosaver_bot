package eu.neydev.saver.core.extract;

/**
 * The extraction SPI. A backend knows one technology (yt-dlp, gallery-dl, the built-in
 * OpenGraph scraper, plain direct links) and nothing about platforms, jobs or users.
 * New technology = one implementation + one line in the chain wiring; that is the whole
 * extension contract.
 */
public interface Extractor {

    /** Stable id, matches {@link eu.neydev.saver.core.source.SourceBackend#id()}. */
    String backendId();

    /**
     * Can this backend run at all right now (binary present, config sane)? The chain
     * skips unavailable backends with a TOOL_MISSING note instead of failing on them.
     */
    boolean available();

    /**
     * Downloads the request into {@code request.workDir()} and reports what appeared.
     * Must not return an empty result without throwing - "no items" IS a failure the
     * chain wants to classify.
     */
    ExtractionResult extract(ExtractionRequest request) throws ExtractionException;

}
