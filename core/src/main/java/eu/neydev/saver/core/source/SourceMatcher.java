package eu.neydev.saver.core.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URL -> source resolution. Host matching is a suffix match on the normalized host
 * (lowercase, userinfo dropped, mobile prefixes stripped): a catalog pattern
 * {@code youtube.com} covers {@code m.youtube.com}, {@code music.youtube.com} and
 * {@code www.youtube.com} alike. The LONGEST matching pattern wins, so a dedicated
 * {@code music.youtube.com} entry would beat the generic one - specificity is
 * expressible without ordering tricks in the YAML.
 *
 * <p>An unmatched host is not an error: it resolves to {@link SourceCatalog#GENERIC},
 * and the extractor chain tries its generic OpenGraph/yt-dlp path. "Support everything
 * that has a public page" is the product promise; the catalog only routes the KNOWN
 * sites to the best tool.
 */
public final class SourceMatcher {

    private static final String[] MOBILE_PREFIXES = {"www.", "m.", "mobile.", "touch.", "amp."};

    private final SourceCatalogHolder holder;

    public SourceMatcher(SourceCatalogHolder holder) {
        this.holder = holder;
    }

    /** Convenience for tests that build a catalog directly. */
    public SourceMatcher(SourceCatalog catalog) {
        this(SourceCatalogHolder.of(catalog));
    }

    public Source match(String url) {

        String host = hostOf(url);

        if (host == null) {
            return SourceCatalog.GENERIC;
        }

        Source best = null;
        int bestLength = -1;

        for (Source source : holder.catalog().all()) {

            for (String pattern : source.hosts()) {

                if (hostMatches(host, pattern) && pattern.length() > bestLength) {
                    best = source;
                    bestLength = pattern.length();
                }

            }

        }

        return best != null ? best : SourceCatalog.GENERIC;

    }

    /**
     * Normalized host of a URL, or {@code null} when the string is not a parseable
     * http(s) URL. Public because the command layer shows the host in status messages.
     */
    public static String hostOf(String url) {

        try {

            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();

            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }

            String host = uri.getHost();

            if (host == null || host.isBlank()) {
                return null;
            }

            return stripMobile(host.toLowerCase(Locale.ROOT));

        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }

    }

    private static String stripMobile(String host) {

        for (String prefix : MOBILE_PREFIXES) {

            // never strip the prefix away from a bare two-label host ("m.ru" is a domain)
            if (host.startsWith(prefix) && host.length() > prefix.length() + 3) {
                return host.substring(prefix.length());
            }

        }

        return host;

    }

    /** Suffix match on dot boundaries: "youtu.be" must not match "notyoutu.be". */
    static boolean hostMatches(String host, String pattern) {

        if (pattern.startsWith(".")) {
            pattern = pattern.substring(1);
        }

        return host.equals(pattern) || host.endsWith("." + pattern);

    }

}
