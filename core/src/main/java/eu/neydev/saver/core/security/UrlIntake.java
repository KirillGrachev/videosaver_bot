package eu.neydev.saver.core.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a user's message into clean download candidates. People paste links inside
 * sentences, inside Telegram's <url> wrappers, with trailing punctuation, several per
 * message - the intake normalizes all of it and rejects what must never be fetched
 * (non-http schemes, credential-bearing URLs). Validation is deliberately SEPARATE
 * from {@link SsrfGuard}: intake decides "is this a link at all", the guard decides
 * "may we connect there".
 */
public final class UrlIntake {

    /** Hard cap per message: a paste-bomb must not become a download fork-bomb. */
    public static final int MAX_URLS_PER_MESSAGE = 5;

    private static final java.util.regex.Pattern URL =
            java.util.regex.Pattern.compile("https?://[^\\s<>\"'\\u0000-\\u001f]+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final String TRAILING = ".,!?;:'\")]}>\u00bb\u201d";

    /** Thrown for a single rejected URL; the caller reports it and keeps the rest. */
    public static class InvalidUrlException extends RuntimeException {

        public InvalidUrlException(String message) {
            super(message);
        }

    }

    /** Finds URLs in free text, in order, deduplicated, capped at {@value #MAX_URLS_PER_MESSAGE}. */
    public List<String> extractUrls(String text) {

        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> found = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = URL.matcher(text);

        while (matcher.find() && found.size() < MAX_URLS_PER_MESSAGE) {
            String candidate = trim(matcher.group());
            if (!candidate.isBlank()) {
                found.add(candidate);
            }
        }

        return new ArrayList<>(found);

    }

    public boolean containsUrl(String text) {
        return text != null && URL.matcher(text).find();
    }

    /**
     * Validates and normalizes one URL for fetching: http(s) only, no userinfo,
     * a parseable host. Trailing punctuation from chat text is already trimmed by
     * {@link #extractUrls}; this is the second net for URLs that arrived by other paths.
     */
    public URI validate(String rawUrl) {

        String url = trim(rawUrl.trim());

        URI uri;

        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("Not a valid URL: " + rawUrl);
        }

        String scheme = uri.getScheme();

        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidUrlException("Only http(s) links can be saved: " + rawUrl);
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("URL without a host: " + rawUrl);
        }

        if (uri.getUserInfo() != null) {
            throw new InvalidUrlException("URLs with credentials are not accepted");
        }

        return uri;

    }

    /**
     * Trims chat punctuation from the tail, with the classic URL-vs-sentence test for
     * parentheses: strip a trailing ')' only when the URL has more closes than opens
     * (Wikipedia links legitimately END with a balanced parenthesis).
     */
    private static String trim(String url) {

        String result = url;

        boolean changed = true;

        while (changed && !result.isEmpty()) {

            changed = false;
            char last = result.charAt(result.length() - 1);

            if (last == ')') {

                long open = result.chars().filter(c -> c == '(').count();
                long close = result.chars().filter(c -> c == ')').count();

                if (close > open) {
                    result = result.substring(0, result.length() - 1);
                    changed = true;
                }

                continue;

            }

            if (TRAILING.indexOf(last) >= 0) {
                result = result.substring(0, result.length() - 1);
                changed = true;
            }

        }

        return result;

    }

}
