package eu.neydev.saver.core;

import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.command.CommandRouter;
import eu.neydev.saver.core.command.MenuFactory;
import eu.neydev.saver.core.command.JobReplies;
import eu.neydev.saver.core.command.ReplyBuilder;
import eu.neydev.saver.core.command.handlers.AdminHandlers;
import eu.neydev.saver.core.command.handlers.DownloadHandlers;
import eu.neydev.saver.core.command.handlers.MenuHandlers;
import eu.neydev.saver.core.command.handlers.SettingsHandlers;
import eu.neydev.saver.core.command.handlers.SourcesHandlers;
import eu.neydev.saver.core.config.AppConfig;
import eu.neydev.saver.core.conversation.ConversationStore;
import eu.neydev.saver.core.download.FileVault;
import eu.neydev.saver.core.download.JobManager;
import eu.neydev.saver.core.download.SizePolicy;
import eu.neydev.saver.core.extract.ExtractedItem;
import eu.neydev.saver.core.extract.ExtractionRequest;
import eu.neydev.saver.core.extract.ExtractionResult;
import eu.neydev.saver.core.extract.Extractor;
import eu.neydev.saver.core.extract.ExtractorChain;
import eu.neydev.saver.core.extract.ProgressListener;
import eu.neydev.saver.core.extract.backend.Toolchain;
import eu.neydev.saver.core.i18n.MessageBundleHolder;
import eu.neydev.saver.core.media.MediaKind;
import eu.neydev.saver.core.media.MediaProbe;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.metrics.PlatformHealth;
import eu.neydev.saver.core.pipeline.OutboundDispatcher;
import eu.neydev.saver.core.security.UrlIntake;
import eu.neydev.saver.core.service.UserService;
import eu.neydev.saver.core.source.SourceCatalog;
import eu.neydev.saver.core.source.SourceCatalogHolder;
import eu.neydev.saver.core.source.SourceMatcher;
import eu.neydev.saver.core.storage.JdbcStorage;
import eu.neydev.saver.core.util.ByteFormat;
import eu.neydev.saver.core.webapp.MediaLinkServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The full bot wired for tests: real router, real job manager, real storage (SQLite in
 * a temp dir), real dispatcher - but a FAKE platform ({@link FakeAdapter}) and FAKE
 * extractors. Everything a user-visible behavior test needs, without a single socket
 * to the outside world.
 *
 * <p>Message texts come from the small core test bundle, so most templates render as
 * {@code [missing:...]} placeholders: these tests assert MESSAGE TYPES and STORAGE
 * EFFECTS, never wording (wording is the app module's MessageKeysTest territory).
 */
public final class TestBot implements AutoCloseable {

    public final MetricsRegistry metrics = new MetricsRegistry();
    public final FakeAdapter adapter = new FakeAdapter(Platform.TELEGRAM);
    public final JdbcStorage storage;
    public final OutboundDispatcher dispatcher;
    public final CommandRouter router;
    public final JobManager jobManager;
    public final FileVault vault;
    public final SourceCatalog catalog;
    public final ConversationStore conversations;
    public final AppConfig config;
    public final ReplyBuilder replies;
    public final UserService userService;
    public final Clock clock = Clock.systemUTC();

    /** Optional decorator over the job repository - tests that break storage on purpose. */
    public static java.util.function.UnaryOperator<eu.neydev.saver.core.storage.JobRepository>
            jobsOverride;

    private TestBot(AppConfig config, Path vaultDir, List<Extractor> extractors) {
        this(config, vaultDir, extractors, new MediaLinkServer(null, Duration.ofMinutes(5)));
    }

    private TestBot(AppConfig config, Path vaultDir, List<Extractor> extractors,
                    MediaLinkServer mediaLinks) {

        this.config = config;

        storage = JdbcStorage.open(config.storage(), clock);

        MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                config.locale().supported(), config.locale().defaultLanguage(), metrics);
        MenuFactory menus = new MenuFactory(holder, config);
        JobReplies jobReplies = new JobReplies(holder, menus);
        SourceCatalogHolder catalogHolder =
                SourceCatalogHolder.of(SourceCatalog.load(Set.copyOf(config.sources().disabled()), null));
        catalog = catalogHolder.catalog();
        replies = new ReplyBuilder(holder, menus, config, catalogHolder, clock);
        userService = new UserService(storage.users(),
                new eu.neydev.saver.core.i18n.LocaleResolver(config.locale().supported(),
                        config.locale().defaultLanguage()), config, clock);
        conversations = new ConversationStore(Duration.ofMinutes(5));

        dispatcher = new OutboundDispatcher(2, 1, 10, 10_000, 10_000, metrics);
        dispatcher.register(adapter);
        dispatcher.start();

        vault = new FileVault(vaultDir, config.downloader().vault().maxBytes(),
                config.downloader().vault().ttl(), config.downloader().vault().sendGrace(),
                metrics);
        vault.start();

        Toolchain tools = Toolchain.probe("saver-test-missing-ytdlp",
                "saver-test-missing-gallerydl", "saver-test-missing-ffmpeg");

        eu.neydev.saver.core.storage.JobRepository jobRepo =
                jobsOverride != null ? jobsOverride.apply(storage.jobs()) : storage.jobs();

        jobManager = new JobManager(config, catalogHolder, new SourceMatcher(catalogHolder),
                new UrlIntake(), new ExtractorChain(extractors), vault,
                new SizePolicy(config.delivery()), new MediaProbe(null), tools,
                jobRepo, storage.usage(),
                dispatcher, jobReplies, metrics, clock, mediaLinks);
        jobManager.start();

        MenuHandlers menuHandlers = new MenuHandlers(replies);
        DownloadHandlers downloadHandlers = new DownloadHandlers(jobManager, replies,
                jobReplies, conversations, new UrlIntake());
        SettingsHandlers settingsHandlers = new SettingsHandlers(replies, userService,
                storage.jobs(), storage.usage(), clock);
        SourcesHandlers sourcesHandlers = new SourcesHandlers(replies, catalogHolder, conversations);
        AdminHandlers adminHandlers = new AdminHandlers(replies, holder, catalogHolder,
                jobManager, vault, storage.jobs(), userService);

        router = new CommandRouter(userService, conversations, replies, jobReplies, menuHandlers,
                downloadHandlers, settingsHandlers, sourcesHandlers, adminHandlers,
                dispatcher, metrics, new UrlIntake(), config);

    }

    /** A bot whose extractor "downloads" one file of the given size and kind. */
    public static TestBot standard(Path dir, MediaKind kind, long bytes) {

        return new TestBot(config(dir, 2, 20, ByteFormat.parse("2gb", "t"),
                defaultDelivery(), ByteFormat.parse("1gb", "t")),
                vaultDir(dir), List.of(fileExtractor(kind, bytes)));

    }

    public static TestBot with(Path dir, AppConfig config, List<Extractor> extractors) {
        return new TestBot(config, vaultDir(dir), extractors);
    }

    /** A bot whose webapp is publicly reachable: oversized files fall back to our link. */
    public static TestBot withPublicMediaLinks(Path dir, AppConfig config,
                                                 List<Extractor> extractors, String publicUrl) {
        return new TestBot(config, vaultDir(dir), extractors,
                new MediaLinkServer(publicUrl, Duration.ofMinutes(5)));
    }

    public static Path vaultDir(Path dir) {

        try {
            return Files.createDirectories(dir.resolve("vault"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

    }

    /** One canned file per extraction - the "download" every happy-path test needs. */
    public static Extractor fileExtractor(MediaKind kind, long bytes) {

        return new Extractor() {

            @Override
            public String backendId() {
                return "fake";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {

                try {

                    Path file = request.workDir().resolve("media." + extension(kind));
                    Files.write(file, new byte[(int) Math.min(bytes, 64 * 1024)]);

                    // The declared size drives the size policy even when the physical
                    // file is a stub: tests must be able to simulate 60 MB cheaply.
                    ExtractedItem item = new ExtractedItem(kind, file, bytes,
                            "Fake Title", kind == MediaKind.VIDEO ? 1280 : null,
                            kind == MediaKind.VIDEO ? 720 : null,
                            kind == MediaKind.VIDEO ? 90 : null,
                            request.url().toString());

                    request.progress().onProgress(100, null, 1, 1);

                    return new ExtractionResult(request.source(), "fake", List.of(item),
                            "Fake Title", "fake-uploader", request.url().toString(), false);

                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }

            }

        };

    }

    /**
     * One canned file per extraction that RECORDS the request: what quality was asked
     * and how many times the tool ran (cache and per-request-quality tests assert on it).
     */
    public static Extractor recordingExtractor(MediaKind kind, long bytes,
                                               java.util.concurrent.atomic.AtomicInteger calls,
                                               java.util.concurrent.atomic.AtomicReference<eu.neydev.saver.core.extract.ExtractionRequest> last) {

        Extractor inner = fileExtractor(kind, bytes);

        return new Extractor() {

            @Override
            public String backendId() {
                return inner.backendId();
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {
                calls.incrementAndGet();
                last.set(request);
                return inner.extract(request);
            }

        };

    }

    /** A "download" that produces a blocked executable - for the malware-guard test. */
    public static Extractor exeExtractor() {

        return new Extractor() {

            @Override
            public String backendId() {
                return "exe";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {

                try {

                    Path file = request.workDir().resolve("totally-safe.exe");
                    Files.write(file, new byte[]{1, 2, 3});

                    return new ExtractionResult(request.source(), "exe",
                            List.of(ExtractedItem.of(MediaKind.DOCUMENT, file)),
                            null, null, request.url().toString(), false);

                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }

            }

        };

    }

    /** An extractor that never finishes until cancelled - for the /stop test. */
    public static Extractor slowExtractor() {

        return new Extractor() {

            @Override
            public String backendId() {
                return "slow";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ExtractionResult extract(ExtractionRequest request) {

                while (!request.progress().cancelled()
                        && !Thread.currentThread().isInterrupted()) {

                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                }

                throw new eu.neydev.saver.core.extract.ExtractionException(
                        eu.neydev.saver.core.extract.ExtractionException.Category.CANCELLED,
                        "slow", "cancelled");

            }

        };

    }

    private static String extension(MediaKind kind) {

        return switch (kind) {
            case PHOTO -> "jpg";
            case VIDEO -> "mp4";
            case AUDIO -> "mp3";
            case GIF -> "gif";
            case DOCUMENT -> "bin";
        };

    }

    public static AppConfig.Delivery defaultDelivery() {

        return new AppConfig.Delivery(Map.of(
                Platform.TELEGRAM, new AppConfig.Delivery.SizeCaps(
                        10_000_000, 50_000_000, 50_000_000, 50_000_000, 50_000_000)),
                true);

    }

    public static AppConfig config(Path dir, int perUserConcurrent, int hourQuota,
                                   long bytesPerUserPerDay, AppConfig.Delivery delivery,
                                   long vaultMax) {

        return new AppConfig(
                new AppConfig.Storage(AppConfig.Storage.Type.SQLITE,
                        dir.resolve("test.db").toString(), null, null, null, 2),
                new AppConfig.Downloader(2, 20, perUserConcurrent, 10,
                        Duration.ofSeconds(30), Duration.ofSeconds(60),
                        ByteFormat.parse("500mb", "t"),
                        eu.neydev.saver.core.extract.QualityPreset.BEST,
                        true, null, null,
                        false, 1000.0, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(2),
                        false, false, List.of("exe"),
                        AppConfig.Downloader.Tools.DEFAULT,
                        new AppConfig.Downloader.Vault(dir.resolve("vault").toString(),
                                vaultMax, Duration.ofMinutes(30), Duration.ofSeconds(30))),
                new AppConfig.Limits(hourQuota, 1000, bytesPerUserPerDay),
                delivery,
                new AppConfig.Pipeline(100, 2, 8, 2, 1000, 1000, Duration.ofSeconds(5)),
                new AppConfig.WebApp(false, "127.0.0.1", 18099, null,
                        Duration.ofMinutes(5), null),
                new AppConfig.Locale("en", Set.of("en", "ru")),
                new AppConfig.Sources(List.of()),
                new AppConfig.Community(null),
                Map.of(),
                List.of("telegram:1"),
                AppConfig.Status.DEFAULT);

    }

    /** Polls until the condition holds; fails the test loudly instead of hanging. */
    public static void waitUntil(BooleanSupplier condition, String what) {

        long deadline = System.currentTimeMillis() + 15_000;

        while (System.currentTimeMillis() < deadline) {

            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

        }

        throw new AssertionError("Timed out waiting for: " + what);

    }

    @Override
    public void close() {

        jobManager.close();
        dispatcher.close();
        vault.close();
        storage.close();

    }

    /** Platform health stub registration helper (unused by router tests, kept for parity). */
    public PlatformHealth health() {
        return new PlatformHealth();
    }

}
