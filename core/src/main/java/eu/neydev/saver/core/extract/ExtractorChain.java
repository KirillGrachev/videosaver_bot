package eu.neydev.saver.core.extract;

import eu.neydev.saver.core.source.Source;
import eu.neydev.saver.core.source.SourceBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered fallback over the registered backends: the source's hinted backend runs first,
 * then the rest in a fixed order of decreasing specialization. A gallery site whose post
 * turns out to be a video still gets extracted (gallery-dl fails, yt-dlp succeeds); an
 * unknown site gets the full ladder. The FIRST success wins; when everything fails, the
 * MOST SPECIFIC error is reported, so "login required" beats a scraper's "nothing found".
 */
public final class ExtractorChain {

    private static final Logger log = LoggerFactory.getLogger(ExtractorChain.class);

    private final Map<String, Extractor> byBackend = new LinkedHashMap<>();

    public ExtractorChain(List<Extractor> extractors) {
        extractors.forEach(extractor -> byBackend.put(extractor.backendId(), extractor));
    }

    public ExtractionResult extract(ExtractionRequest request) throws ExtractionException {

        List<ExtractionException> failures = new ArrayList<>();

        // The ladder for the source first, then ANY other registered backend: a custom
        // extractor with its own id must still run as a fallback instead of being
        // silently unreachable.
        List<String> ladder = new ArrayList<>(orderFor(request.source()));

        for (String backendId : byBackend.keySet()) {

            if (!ladder.contains(backendId)) {
                ladder.add(backendId);
            }

        }

        for (String backendId : ladder) {

            Extractor extractor = byBackend.get(backendId);

            if (extractor == null) {
                continue;
            }

            if (!extractor.available()) {

                failures.add(new ExtractionException(ExtractionException.Category.TOOL_MISSING,
                        backendId, "Backend tool is not installed: " + backendId));
                continue;

            }

            try {

                ExtractionResult result = extractor.extract(request);

                if (!result.isEmpty()) {

                    if (!failures.isEmpty()) {
                        log.debug("Backend {} succeeded after {} earlier failure(s) for {}",
                                backendId, failures.size(), request.url());
                    }

                    return result;

                }

                failures.add(new ExtractionException(ExtractionException.Category.UNKNOWN,
                        backendId, "Backend produced no files"));

            } catch (ExtractionException e) {

                if (e.category() == ExtractionException.Category.CANCELLED) {
                    throw e;
                }

                log.debug("Backend {} failed for {}: {} ({})",
                        backendId, request.url(), e.getMessage(), e.category());
                failures.add(e);

            } catch (RuntimeException e) {

                log.warn("Backend {} crashed for {}", backendId, request.url(), e);
                failures.add(new ExtractionException(ExtractionException.Category.UNKNOWN,
                        backendId, e.getMessage() != null ? e.getMessage() : e.toString(), e));

            }

        }

        throw ExtractionException.mostSpecific(failures);

    }

    /**
     * The ladder for a source: its own backend first, then the generalists in order of
     * how much they know about structured content (yt-dlp's generic extractor beats a
     * bare OpenGraph scrape on anything with a player), direct links last.
     */
    List<String> orderFor(Source source) {

        SourceBackend backend = source.backend();

        return switch (backend) {

            case YTDLP -> List.of("ytdlp", "gallerydl", "http", "direct");
            case GALLERYDL -> List.of("gallerydl", "ytdlp", "http", "direct");
            case HTTP -> List.of("http", "ytdlp", "gallerydl", "direct");
            case DIRECT -> List.of("direct", "http", "ytdlp");

        };

    }

    /** Which backends are installed and usable - for /healthz and the admin panel. */
    public Map<String, Boolean> availability() {

        Map<String, Boolean> result = new LinkedHashMap<>();
        byBackend.forEach((id, extractor) -> result.put(id, extractor.available()));

        return result;

    }

}
