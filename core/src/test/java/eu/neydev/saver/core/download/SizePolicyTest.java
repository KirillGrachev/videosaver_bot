package eu.neydev.saver.core.download;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.media.MediaKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SizePolicyTest {

    private static final long MB = 1_000_000L;

    private static AppConfig.Delivery delivery(boolean linkFallback) {

        return new AppConfig.Delivery(Map.of(
                Platform.TELEGRAM, new AppConfig.Delivery.SizeCaps(
                        10 * MB, 50 * MB, 50 * MB, 50 * MB, 50 * MB),
                Platform.DISCORD, AppConfig.Delivery.SizeCaps.uniform(25 * MB),
                Platform.VIBER, new AppConfig.Delivery.SizeCaps(
                        1 * MB, 26 * MB, 50 * MB, 50 * MB, 50 * MB)),
                linkFallback);

    }

    @Test
    void filesUnderTheCapPassAsIs() {

        SizePolicy policy = new SizePolicy(delivery(true));

        assertThat(policy.check(Platform.TELEGRAM, MediaKind.VIDEO, 49 * MB))
                .isEqualTo(SizePolicy.Verdict.OK);
        assertThat(policy.check(Platform.TELEGRAM, MediaKind.PHOTO, 9 * MB))
                .isEqualTo(SizePolicy.Verdict.OK);

    }

    @Test
    void oversizedVideoDegradesToDocumentWhenItFitsThere() {

        SizePolicy policy = new SizePolicy(delivery(true));

        // A 60 MB video exceeds the 50 MB video cap; with a 200 MB document cap
        // (VK-style) it still travels - as a document.
        AppConfig.Delivery vk = new AppConfig.Delivery(Map.of(
                Platform.VK, new AppConfig.Delivery.SizeCaps(
                        50 * MB, 50 * MB, 200 * MB, 200 * MB, 200 * MB)), true);

        assertThat(new SizePolicy(vk).check(Platform.VK, MediaKind.VIDEO, 60 * MB))
                .isEqualTo(SizePolicy.Verdict.AS_DOCUMENT);

        // Telegram's document cap equals the video cap: nothing to degrade into.
        assertThat(policy.check(Platform.TELEGRAM, MediaKind.VIDEO, 60 * MB))
                .isEqualTo(SizePolicy.Verdict.LINK_FALLBACK);

    }

    @Test
    void photosNeverDegradeToDocuments() {

        SizePolicy policy = new SizePolicy(delivery(true));

        // A 4 MB photo on Viber (1 MB cap) must not become a "document photo" -
        // it is a link fallback instead.
        assertThat(policy.check(Platform.VIBER, MediaKind.PHOTO, 4 * MB))
                .isEqualTo(SizePolicy.Verdict.LINK_FALLBACK);

    }

    @Test
    void linkFallbackCanBeDisabled() {

        SizePolicy policy = new SizePolicy(delivery(false));

        assertThat(policy.check(Platform.TELEGRAM, MediaKind.VIDEO, 60 * MB))
                .isEqualTo(SizePolicy.Verdict.REJECT);

    }

    @Test
    void downloadCapIsTheLargestDeliverableKind() {

        SizePolicy policy = new SizePolicy(delivery(true));

        assertThat(policy.maxDownloadBytes(Platform.TELEGRAM)).isEqualTo(50 * MB);
        assertThat(policy.maxDownloadBytes(Platform.DISCORD)).isEqualTo(25 * MB);
        assertThat(policy.maxDownloadBytes(Platform.VIBER)).isEqualTo(50 * MB);

    }

    @Test
    void unknownPlatformsGetAConservativeDefault() {

        SizePolicy policy = new SizePolicy(new AppConfig.Delivery(Map.of(), true));

        assertThat(policy.capFor(Platform.SLACK, MediaKind.VIDEO)).isEqualTo(8 * MB);

    }

}
