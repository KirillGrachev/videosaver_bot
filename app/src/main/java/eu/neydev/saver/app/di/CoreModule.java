package eu.neydev.saver.app.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import eu.neydev.saver.core.command.CommandRouter;
import eu.neydev.saver.core.command.JobReplies;
import eu.neydev.saver.core.command.MenuFactory;
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
import eu.neydev.saver.core.extract.ExtractorChain;
import eu.neydev.saver.core.extract.backend.DirectLinkExtractor;
import eu.neydev.saver.core.extract.backend.GalleryDlExtractor;
import eu.neydev.saver.core.extract.backend.GenericHttpExtractor;
import eu.neydev.saver.core.extract.backend.SafeHttp;
import eu.neydev.saver.core.extract.backend.ToolUpdater;
import eu.neydev.saver.core.extract.backend.ToolProvisioner;
import eu.neydev.saver.core.extract.backend.Toolchain;
import eu.neydev.saver.core.extract.backend.YtDlpExtractor;
import eu.neydev.saver.core.media.MediaProbe;
import eu.neydev.saver.core.security.SsrfFilterProxy;
import eu.neydev.saver.core.i18n.LocaleResolver;
import eu.neydev.saver.core.i18n.MessageBundleHolder;
import eu.neydev.saver.core.metrics.MetricsRegistry;
import eu.neydev.saver.core.metrics.PlatformHealth;
import eu.neydev.saver.core.pipeline.InboundRouter;
import eu.neydev.saver.core.pipeline.OutboundDispatcher;
import eu.neydev.saver.core.security.SsrfGuard;
import eu.neydev.saver.core.security.UrlIntake;
import eu.neydev.saver.core.service.UserService;
import eu.neydev.saver.core.source.SourceCatalogHolder;
import eu.neydev.saver.core.source.SourceMatcher;
import eu.neydev.saver.core.storage.JobRepository;
import eu.neydev.saver.core.storage.JdbcStorage;
import eu.neydev.saver.core.storage.UsageRepository;
import eu.neydev.saver.core.storage.UserRepository;
import eu.neydev.saver.core.webapp.HealthServer;
import eu.neydev.saver.core.webapp.MediaLinkServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whole object graph of the core, assembled by Guice. Each service is an explicit
 * {@code @Provides @Singleton} with constructor dependencies: the graph stays
 * traceable, not a single hidden dependency via field reflection.
 *
 * <p>Every {@code @Provides} method below is called by Guice at injection time and never
 * from hand-written code: an IDE "method is never used" warning on them is expected
 * and must stay a warning, not become a deletion.
 */
public final class CoreModule extends AbstractModule {

    private final AppConfig config;
    private final Clock clock;

    public CoreModule(AppConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @Override
    protected void configure() {
        bind(AppConfig.class).toInstance(config);
        bind(Clock.class).toInstance(clock);
    }

    @Provides
    @Singleton
    MetricsRegistry metrics() {
        return new MetricsRegistry();
    }

    @Provides
    @Singleton
    JdbcStorage storage(AppConfig config, Clock clock) {
        return JdbcStorage.open(config.storage(), clock);
    }

    @Provides
    UserRepository users(JdbcStorage storage) {
        return storage.users();
    }

    @Provides
    JobRepository jobs(JdbcStorage storage) {
        return storage.jobs();
    }

    @Provides
    UsageRepository usage(JdbcStorage storage) {
        return storage.usage();
    }

    @Provides
    @Singleton
    MessageBundleHolder bundleHolder(AppConfig config, MetricsRegistry metrics) {
        Path external = Path.of("config/messages");
        return new MessageBundleHolder("messages",
                Files.isDirectory(external) ? external : null,
                config.locale().supported(), config.locale().defaultLanguage(), metrics);
    }

    @Provides
    @Singleton
    PlatformHealth platformHealth() {
        return new PlatformHealth();
    }

    @Provides
    @Singleton
    LocaleResolver localeResolver(AppConfig config) {
        return new LocaleResolver(config.locale().supported(), config.locale().defaultLanguage());
    }

    @Provides
    @Singleton
    ToolProvisioner toolProvisioner(AppConfig config, MetricsRegistry metrics) {
        return new ToolProvisioner(config.downloader().tools(), metrics);
    }

    @Provides
    @Singleton
    Toolchain toolchain(AppConfig config, ToolProvisioner provisioner) {

        AppConfig.Downloader.Tools tools = config.downloader().tools();

        Toolchain toolchain = Toolchain.probe(tools.ytDlp(), tools.galleryDl(),
                tools.ffmpeg(), tools.ffprobe());

        if (!tools.provision()) {
            return toolchain;
        }

        // Self-provisioning: missing tools get pointed at their future home in the
        // provision dir NOW, so the re-probe after each landed download flips them to
        // present without a restart; the downloads themselves run in the background,
        // because startup must never wait for a 150 MB archive.
        Map<String, String> missing = provisioner.plan(toolchain);

        if (!missing.isEmpty()) {
            toolchain.redirect(missing);
            provisioner.installAsync(missing, toolchain::reprobe);
        }

        return toolchain;

    }

    @Provides
    @Singleton
    SsrfGuard ssrfGuard(AppConfig config) {

        if (config.downloader().allowPrivateNetworks()) {
            org.slf4j.LoggerFactory.getLogger(CoreModule.class).warn(
                    "downloader.allow-private-networks is TRUE: the SSRF guard is off. "
                            + "Never enable this on a public deployment");
        }

        return new SsrfGuard(config.downloader().allowPrivateNetworks());

    }

    @Provides
    @Singleton
    SafeHttp safeHttp(SsrfGuard guard) {
        return new SafeHttp(guard, Duration.ofSeconds(45));
    }

    /**
     * The proxy the TOOLS use: an explicit operator proxy wins (with a warning that it
     * bypasses the guard), otherwise - when the guard is on - the in-process filter
     * proxy, so yt-dlp/gallery-dl cannot be bounced into the LAN by page content.
     */
    private static String toolProxy(AppConfig config, SsrfFilterProxy filterProxy) {

        String explicit = config.downloader().proxy();

        if (explicit != null && !explicit.isBlank()) {

            if (!config.downloader().allowPrivateNetworks()) {
                org.slf4j.LoggerFactory.getLogger(CoreModule.class).warn(
                        "downloader.proxy is set: extraction tools bypass the SSRF filter "
                                + "proxy and reach the network through {} - the operator owns "
                                + "that path", explicit);
            }

            return explicit;

        }

        return config.downloader().allowPrivateNetworks() ? null : filterProxy.url();

    }

    @Provides
    @Singleton
    SsrfFilterProxy ssrfFilterProxy(SsrfGuard guard, MetricsRegistry metrics, AppConfig config) {

        SsrfFilterProxy proxy = new SsrfFilterProxy(guard, metrics);

        // The extractors bake this address into their options AT GRAPH CONSTRUCTION
        // TIME, i.e. before Bootstrap.start() runs. Constructing without listening
        // handed them http://127.0.0.1:-1 and every tool request died on an
        // unroutable proxy (the 2026-10 TOOL_MISSING-follow-up incident). start() is
        // idempotent, so the call left in Bootstrap.start() is a harmless no-op.
        if (!config.downloader().allowPrivateNetworks()) {

            try {
                proxy.start();
            } catch (java.io.IOException e) {
                throw new com.google.inject.ProvisionException(
                        "SSRF filter proxy cannot bind its loopback port", e);
            }

        }

        return proxy;

    }

    @Provides
    @Singleton
    ToolUpdater toolUpdater(Toolchain tools, AppConfig config, MetricsRegistry metrics) {
        return new ToolUpdater(tools, config.downloader().tools().updateInterval(), metrics);
    }

    @Provides
    @Singleton
    MediaProbe mediaProbe(Toolchain tools) {
        return new MediaProbe(tools.hasFfprobe() ? tools.ffprobe().command() : null);
    }

    @Provides
    @Singleton
    YtDlpExtractor ytDlpExtractor(Toolchain tools, AppConfig config, MetricsRegistry metrics,
                                  SsrfFilterProxy filterProxy) {

        // Startup hook: a world-readable cookies file is a silent account leak -
        // warn loudly once, here, before the first request can use it.
        eu.neydev.saver.core.security.CookieFileGuard
                .warnIfLoose(config.downloader().cookiesFile());

        return new YtDlpExtractor(tools, new YtDlpExtractor.Options(
                toolProxy(config, filterProxy),
                config.downloader().cookiesFile(),
                config.downloader().extractTimeout(),
                config.downloader().downloadTimeout(),
                4,
                config.downloader().sleepRequests(),
                config.downloader().writeSubs(),
                config.downloader().audioThumbnail()), metrics);

    }

    @Provides
    @Singleton
    GalleryDlExtractor galleryDlExtractor(Toolchain tools, AppConfig config,
                                          SsrfFilterProxy filterProxy) {

        return new GalleryDlExtractor(tools, new GalleryDlExtractor.Options(
                toolProxy(config, filterProxy),
                config.downloader().cookiesFile(),
                config.downloader().downloadTimeout()));

    }

    @Provides
    @Singleton
    GenericHttpExtractor genericHttpExtractor(SafeHttp http) {
        return new GenericHttpExtractor(http);
    }

    @Provides
    @Singleton
    DirectLinkExtractor directLinkExtractor(SafeHttp http) {
        return new DirectLinkExtractor(http);
    }

    @Provides
    @Singleton
    ExtractorChain extractorChain(YtDlpExtractor ytDlp, GalleryDlExtractor galleryDl,
                                  GenericHttpExtractor generic, DirectLinkExtractor direct,
                                  ToolProvisioner provisioner) {

        return new ExtractorChain(List.of(ytDlp, galleryDl, generic, direct), provisioner);

    }

    @Provides
    @Singleton
    SourceCatalogHolder sourceCatalogHolder(AppConfig config) {

        Path external = Path.of("config/sources.yml");

        return new SourceCatalogHolder(Set.copyOf(config.sources().disabled()),
                Files.isReadable(external) ? external : null);

    }

    @Provides
    @Singleton
    SourceMatcher sourceMatcher(SourceCatalogHolder holder) {
        return new SourceMatcher(holder);
    }

    @Provides
    @Singleton
    UrlIntake urlIntake() {
        return new UrlIntake();
    }

    @Provides
    @Singleton
    FileVault fileVault(AppConfig config, MetricsRegistry metrics) {

        AppConfig.Downloader.Vault vault = config.downloader().vault();

        return new FileVault(Path.of(vault.dir()), vault.maxBytes(),
                vault.ttl(), vault.sendGrace(), metrics);

    }

    @Provides
    @Singleton
    SizePolicy sizePolicy(AppConfig config) {
        return new SizePolicy(config.delivery());
    }

    @Provides
    @Singleton
    MediaLinkServer mediaLinkServer(AppConfig config) {

        String publicUrl = config.webApp().enabled() ? config.webApp().publicUrl() : null;

        return new MediaLinkServer(publicUrl, config.webApp().mediaLinkTtl());

    }

    @Provides
    @Singleton
    OutboundDispatcher dispatcher(AppConfig config, MetricsRegistry metrics) {
        return new OutboundDispatcher(
                config.pipeline().outboundWorkersPerPlatform(),
                3,
                config.pipeline().sendTimeout().toMillis(),
                config.pipeline().outboundGlobalPerSecond(),
                config.pipeline().outboundPerChatPerMinute(),
                metrics);
    }

    @Provides
    @Singleton
    MenuFactory menuFactory(MessageBundleHolder holder, AppConfig config) {
        return new MenuFactory(holder, config);
    }

    @Provides
    @Singleton
    ReplyBuilder replyBuilder(MessageBundleHolder holder, MenuFactory menus, AppConfig config,
                              SourceCatalogHolder catalogHolder, Clock clock) {
        return new ReplyBuilder(holder, menus, config, catalogHolder, clock);
    }

    @Provides
    @Singleton
    JobReplies jobReplies(MessageBundleHolder holder, MenuFactory menus) {
        return new JobReplies(holder, menus);
    }

    @Provides
    @Singleton
    UserService userService(UserRepository users, LocaleResolver localeResolver,
                            AppConfig config, Clock clock) {
        return new UserService(users, localeResolver, config, clock);
    }

    @Provides
    @Singleton
    ConversationStore conversationStore() {
        return new ConversationStore(Duration.ofMinutes(5));
    }

    @Provides
    @Singleton
    JobManager jobManager(AppConfig config, SourceCatalogHolder catalogHolder,
                          SourceMatcher matcher, UrlIntake intake, ExtractorChain chain,
                          FileVault vault, SizePolicy sizePolicy, MediaProbe mediaProbe,
                          Toolchain tools, JobRepository jobs, UsageRepository usage,
                          OutboundDispatcher dispatcher, JobReplies jobReplies,
                          MetricsRegistry metrics, Clock clock, MediaLinkServer mediaLinks) {

        return new JobManager(config, catalogHolder, matcher, intake, chain, vault, sizePolicy,
                mediaProbe, tools, jobs, usage, dispatcher, jobReplies, metrics, clock,
                mediaLinks);

    }

    @Provides
    @Singleton
    MenuHandlers menuHandlers(ReplyBuilder replies) {
        return new MenuHandlers(replies);
    }

    @Provides
    @Singleton
    DownloadHandlers downloadHandlers(JobManager jobManager, ReplyBuilder replies,
                                      JobReplies jobReplies, ConversationStore conversations,
                                      UrlIntake intake) {
        return new DownloadHandlers(jobManager, replies, jobReplies, conversations, intake);
    }

    @Provides
    @Singleton
    SettingsHandlers settingsHandlers(ReplyBuilder replies, UserService users,
                                      JobRepository jobs, UsageRepository usage, Clock clock) {
        return new SettingsHandlers(replies, users, jobs, usage, clock);
    }

    @Provides
    @Singleton
    SourcesHandlers sourcesHandlers(ReplyBuilder replies, SourceCatalogHolder catalogHolder,
                                    ConversationStore conversations) {
        return new SourcesHandlers(replies, catalogHolder, conversations);
    }

    @Provides
    @Singleton
    AdminHandlers adminHandlers(ReplyBuilder replies, MessageBundleHolder holder,
                                SourceCatalogHolder catalogHolder, JobManager jobManager,
                                FileVault vault, JobRepository jobs, UserService users) {
        return new AdminHandlers(replies, holder, catalogHolder, jobManager, vault, jobs, users);
    }

    @Provides
    @Singleton
    CommandRouter commandRouter(UserService userService, ConversationStore conversations,
                                ReplyBuilder replies, JobReplies jobReplies,
                                MenuHandlers menuHandlers,
                                DownloadHandlers downloadHandlers,
                                SettingsHandlers settingsHandlers,
                                SourcesHandlers sourcesHandlers, AdminHandlers adminHandlers,
                                OutboundDispatcher dispatcher, MetricsRegistry metrics,
                                UrlIntake intake, AppConfig config) {

        return new CommandRouter(userService, conversations, replies, jobReplies, menuHandlers,
                downloadHandlers, settingsHandlers, sourcesHandlers, adminHandlers,
                dispatcher, metrics, intake, config);

    }

    @Provides
    @Singleton
    InboundRouter inboundRouter(AppConfig config, CommandRouter router, MetricsRegistry metrics) {
        return new InboundRouter(config.pipeline().inboundQueueCapacity(),
                config.pipeline().workerThreads(), router, metrics);
    }

    @Provides
    @Singleton
    HealthServer healthServer(AppConfig config, MetricsRegistry metrics, PlatformHealth health,
                              MediaLinkServer mediaLinks, JobManager jobManager,
                              UserService users, Clock clock) {

        return new HealthServer(config, metrics, health, mediaLinks, jobManager,
                users::count, clock);

    }

}
