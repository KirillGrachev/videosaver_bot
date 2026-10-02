package eu.neydev.saver.core.webapp;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque public links to vault files. One platform family (Viber) does not accept
 * binary uploads: its Bot API fetches media BY URL, so the bot must expose the file
 * for the few seconds the platform needs. The links are:
 *
 * <ul>
 *   <li>UNGUESSABLE: 128 bits of SecureRandom, base64url - the endpoint is public by
 *       necessity, so the token is the whole authorization;</li>
 *   <li>SHORT-LIVED: they die with the vault's send grace period, and the store sweeps
 *       expired entries on every publish (cheap, bounded, no extra thread);</li>
 *   <li>OPT-IN: with no {@code webapp.public-url} nothing is published and the platform
 *       adapter degrades to a text reply with the source link.</li>
 * </ul>
 */
public final class MediaLinkServer {

    private static final Logger log = LoggerFactory.getLogger(MediaLinkServer.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public record MediaLink(Path file, String fileName, String mimeType, Instant expiresAt) {

        public boolean expired(Instant now) {
            return expiresAt.isBefore(now);
        }

    }

    private final @Nullable String publicUrlBase;
    private final Duration ttl;
    private final Map<String, MediaLink> links = new ConcurrentHashMap<>();

    public MediaLinkServer(@Nullable String publicUrlBase, Duration ttl) {

        this.publicUrlBase = publicUrlBase == null || publicUrlBase.isBlank()
                ? null
                : publicUrlBase.endsWith("/")
                        ? publicUrlBase.substring(0, publicUrlBase.length() - 1)
                        : publicUrlBase;
        this.ttl = ttl;

    }

    public boolean publishing() {
        return publicUrlBase != null;
    }

    /** Registers a file and returns its absolute public URL, or empty when disabled. */
    public Optional<String> publish(Path file, String fileName, String mimeType) {

        if (publicUrlBase == null) {
            return Optional.empty();
        }

        sweep(Instant.now());

        byte[] tokenBytes = new byte[16];
        RANDOM.nextBytes(tokenBytes);
        String token = ENCODER.encodeToString(tokenBytes);

        links.put(token, new MediaLink(file, fileName, mimeType, Instant.now().plus(ttl)));

        return Optional.of(publicUrlBase + "/media/" + token);

    }

    public Optional<MediaLink> resolve(String token) {

        MediaLink link = links.get(token);

        if (link == null) {
            return Optional.empty();
        }

        if (link.expired(Instant.now()) || !Files.isRegularFile(link.file())) {
            links.remove(token);
            return Optional.empty();
        }

        return Optional.of(link);

    }

    public int size() {
        return links.size();
    }

    private void sweep(Instant now) {

        if (links.size() > 1000) {
            links.entrySet().removeIf(entry -> entry.getValue().expired(now));
        }

    }

}
