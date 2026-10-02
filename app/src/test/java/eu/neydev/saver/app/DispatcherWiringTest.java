package eu.neydev.saver.app;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.multibindings.Multibinder;
import eu.neydev.saver.app.di.Bootstrap;
import eu.neydev.saver.app.di.CoreModule;
import eu.neydev.saver.core.api.ConnectionProbe;
import eu.neydev.saver.core.api.InlineKeyboard;
import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.pipeline.OutboundDispatcher;
import eu.neydev.saver.core.text.RichText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A regression test for the wiring between {@link Bootstrap} and the outbound dispatcher.
 * The dispatcher sends only to the adapters it was given, and a platform that was never
 * registered makes every reply and every delivered file disappear without a single log
 * line - the bot looks perfectly healthy while staying mute. The graph must never start
 * that way.
 */
class DispatcherWiringTest {

    @TempDir
    Path tempDir;

    @Test
    void aReplySubmittedAfterStartReachesTheAdapter() throws Exception {

        AppConfig config = TestConfigs.minimal(tempDir);
        RecordingAdapter adapter = new RecordingAdapter(Platform.TELEGRAM);

        Injector injector = Guice.createInjector(new CoreModule(config, Clock.systemUTC()));
        Injector full = injector.createChildInjector(new AdapterModule(List.of(adapter)));

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);
        bootstrap.start();

        try {

            full.getInstance(OutboundDispatcher.class)
                    .submit(new OutboundMessage.Send(Platform.TELEGRAM, "42",
                            RichText.plain("ping"), InlineKeyboard.empty(), false))
                    .get(10, TimeUnit.SECONDS);

            assertThat(adapter.sent()).containsExactly("ping");

        } finally {
            bootstrap.stop();
        }

    }

    @Test
    void mediaGroupsExpandForPlatformsWithoutNativeAlbums(@TempDir Path dir2) throws Exception {

        AppConfig config = TestConfigs.minimal(dir2);
        RecordingAdapter adapter = new RecordingAdapter(Platform.TELEGRAM);

        Injector injector = Guice.createInjector(new CoreModule(config, Clock.systemUTC()));
        Injector full = injector.createChildInjector(new AdapterModule(List.of(adapter)));

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);
        bootstrap.start();

        try {

            java.nio.file.Path file = dir2.resolve("a.jpg");
            java.nio.file.Files.write(file, new byte[]{1});

            eu.neydev.saver.core.media.MediaAttachment media =
                    eu.neydev.saver.core.media.MediaAttachment.of(
                            eu.neydev.saver.core.media.MediaKind.PHOTO, file,
                            null, null, null, null, null);

            full.getInstance(OutboundDispatcher.class)
                    .submit(new OutboundMessage.SendMediaGroup(Platform.TELEGRAM, "42",
                            RichText.plain("album"), InlineKeyboard.empty(),
                            List.of(media, media), false))
                    .get(10, TimeUnit.SECONDS);

            // RecordingAdapter does not declare group support: the dispatcher must
            // have expanded the album into two single-file sends.
            assertThat(adapter.mediaSends()).isEqualTo(2);

        } finally {
            bootstrap.stop();
        }

    }

    /** Binds an explicit adapter set on top of the (inactive) platform configuration. */
    private static final class AdapterModule extends AbstractModule {

        private final List<PlatformAdapter> adapters;

        AdapterModule(List<PlatformAdapter> adapters) {
            this.adapters = adapters;
        }

        @Override
        protected void configure() {
            Multibinder<PlatformAdapter> binder =
                    Multibinder.newSetBinder(binder(), PlatformAdapter.class);
            adapters.forEach(adapter -> binder.addBinding().toInstance(adapter));
        }

    }

    /** A platform that is up and records everything the dispatcher hands it. */
    private static final class RecordingAdapter implements PlatformAdapter, ConnectionProbe {

        private final Platform platform;
        private final List<String> sent = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger mediaSends =
                new java.util.concurrent.atomic.AtomicInteger();

        RecordingAdapter(Platform platform) {
            this.platform = platform;
        }

        List<String> sent() {
            return sent;
        }

        int mediaSends() {
            return mediaSends.get();
        }

        @Override
        public Platform platform() {
            return platform;
        }

        @Override
        public boolean connected() {
            return true;
        }

        @Override
        public void start(PlatformContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public SendResult execute(OutboundMessage message) {

            if (message instanceof OutboundMessage.Send send) {
                sent.add(send.text().toPlainText());
            }

            if (message instanceof OutboundMessage.SendMedia) {
                mediaSends.incrementAndGet();
            }

            if (message instanceof OutboundMessage.SendMediaGroup) {
                throw new AssertionError("groups must never reach a non-group adapter");
            }

            return SendResult.ack();

        }

        @Override
        public boolean isEnabled() {
            return true;
        }

    }

}
