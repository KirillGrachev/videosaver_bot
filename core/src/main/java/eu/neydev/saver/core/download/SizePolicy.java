package eu.neydev.saver.core.download;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.media.MediaKind;

/**
 * The size rules per platform, applied BEFORE anything is sent: an oversized upload is
 * not just a failed message, on some platforms it is a failed message AND a burned rate
 * limit. The ladder is deliberate:
 *
 * <ol>
 *   <li>fits the kind's cap -> send as is;</li>
 *   <li>a video/audio/gif too big for its native type but within the DOCUMENT cap ->
 *       send as a document (Telegram/VK users get the file instead of a refusal);</li>
 *   <li>still too big -> the link fallback (the user gets the direct media URL plus
 *       the honest reason), unless the operator disabled the fallback.</li>
 * </ol>
 */
public final class SizePolicy {

    public enum Verdict { OK, AS_DOCUMENT, LINK_FALLBACK, REJECT }

    private final AppConfig.Delivery delivery;

    public SizePolicy(AppConfig.Delivery delivery) {
        this.delivery = delivery;
    }

    public long capFor(Platform platform, MediaKind kind) {

        AppConfig.Delivery.SizeCaps caps = delivery.capsFor(platform);

        return switch (kind) {
            case PHOTO -> caps.photo();
            case VIDEO -> caps.video();
            case AUDIO -> caps.audio();
            case GIF -> caps.gif();
            case DOCUMENT -> caps.document();
        };

    }

    public Verdict check(Platform platform, MediaKind kind, long sizeBytes) {

        if (sizeBytes <= capFor(platform, kind)) {
            return Verdict.OK;
        }

        boolean documentable = kind != MediaKind.DOCUMENT && kind != MediaKind.PHOTO
                && sizeBytes <= capFor(platform, MediaKind.DOCUMENT);

        if (documentable) {
            return Verdict.AS_DOCUMENT;
        }

        return delivery.linkFallback() ? Verdict.LINK_FALLBACK : Verdict.REJECT;

    }

    /**
     * The download cap for a platform: the largest thing it could still receive
     * (usually the document cap). The job manager passes it to the extractors so
     * yt-dlp's {@code --max-filesize} aims at a format that FITS instead of downloading
     * the raw best and failing at delivery.
     */
    public long maxDownloadBytes(Platform platform) {

        AppConfig.Delivery.SizeCaps caps = delivery.capsFor(platform);

        return Math.max(Math.max(caps.video(), caps.document()),
                Math.max(caps.audio(), Math.max(caps.photo(), caps.gif())));

    }

}
