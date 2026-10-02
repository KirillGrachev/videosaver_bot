package eu.neydev.saver.platform.telegram;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.ConnectionProbe;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.PlatformException;
import eu.neydev.saver.core.api.SendResult;
import eu.neydev.saver.core.media.MediaAttachment;
import eu.neydev.saver.core.api.StartupFailureProbe;
import eu.neydev.saver.core.api.UpdateSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.saver.core.config.ConfigException;
import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.exceptions.TelegramApiErrorResponseException;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator;
import org.telegram.telegrambots.longpolling.util.TelegramOkHttpClientFactory;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAnimation;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import eu.neydev.saver.core.text.RichText;

/**
 * A thin Telegram adapter: long polling (module 10.x) + synchronous API calls
 * from the outbound dispatcher threads. All business logic stays in the core.
 *
 * <p>API errors are honestly classified: 429 -> {@link PlatformException.RateLimitedException}
 * with the platform's retry_after; "bot deleted/blocked/chat does not exist" ->
 * {@link PlatformException.PermanentDeliveryException} (the scheduler will disable the subscription).
 *
 * <p>Network unavailability of {@code api.telegram.org} (blocks/firewall) does not break
 * app start: registering long polling goes into a background loop with backoff,
 * the platform is marked alive only after the first successful connection.
 * An optional proxy ({@code platforms.telegram.proxy}) is also passed into
 * the outbound client, and into long polling - they must go the same route.
 */
public final class TelegramAdapter implements PlatformAdapter, StartupFailureProbe, ConnectionProbe {

    private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);

    private static final long RETRY_INITIAL_MILLIS = 5_000;
    private static final long RETRY_MAX_MILLIS = 60_000;
    private static final long STARTUP_WAIT_MILLIS = 10_000;

    private final String token;
    private final @Nullable String webAppUrl;
    private final String webAppTitle;
    private final @Nullable TelegramUrl apiUrl;
    private final @Nullable ProxySpec proxy;
    private final @Nullable PlatformException proxyConfigError;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean preferDirect;
    private volatile boolean routeViaProxy;
    private final Object startLock = new Object();
    private boolean firstResult;
    private volatile PlatformException fatalStartup;
    private volatile OkHttpTelegramClient client;
    private volatile TelegramBotsLongPollingApplication application;
    private volatile Thread retryThread;

    public TelegramAdapter(String token, @Nullable String webAppUrl, String webAppTitle) {
        this(token, webAppUrl, webAppTitle, null, null);
    }

    /** Test hook: the API base URL (a local fake server). */
    public TelegramAdapter(String token, @Nullable String webAppUrl, String webAppTitle,
                           @Nullable TelegramUrl apiUrl) {
        this(token, webAppUrl, webAppTitle, apiUrl, null);
    }

    /** Production constructor: {@code proxy} is {@code [http://|socks5://]host:port} or null. */
    public TelegramAdapter(String token, @Nullable String webAppUrl, String webAppTitle,
                           @Nullable TelegramUrl apiUrl, @Nullable String proxy) {

        this.token = token;
        this.webAppUrl = webAppUrl;
        this.webAppTitle = webAppTitle;
        this.apiUrl = apiUrl;

        ProxySpec parsed = null;
        PlatformException configError = null;

        try {
            parsed = parseProxy(proxy);
        } catch (ConfigException e) {
            // A malformed proxy is a configuration fault of this platform only:
            // the bot keeps running with the rest, the reason is reported once.
            configError = new PlatformException(e.getMessage(), e);
        }

        this.proxy = parsed;
        this.proxyConfigError = configError;
        this.routeViaProxy = parsed != null;

    }

    @Override
    public Platform platform() {
        return Platform.TELEGRAM;
    }

    @Override
    public boolean isEnabled() {
        return token != null && !token.isBlank();
    }

    /** Telegram is the one platform with native albums: sendMediaGroup. */
    @Override
    public boolean supportsMediaGroups() {
        return true;
    }

    @Override
    public void start(PlatformContext context) {

        if (proxyConfigError != null) {

            fatalStartup = proxyConfigError;
            log.error("Telegram: {}", proxyConfigError.getMessage());
            markFirstResult();

            return;

        }

        UpdateSink sink = context.instrumentedSink(Platform.TELEGRAM);
        ensureClient();
        running.set(true);

        Thread starter = new Thread(() -> registerWithRetry(context, sink), "telegram-connect");
        starter.setDaemon(true);
        retryThread = starter;
        starter.start();
        awaitFirstResult();

    }

    /**
     * start() waits for the first registration result (success or fatal
     * error), so an invalid token fails deterministically, not asynchronously.
     * The network timeout is longer than the wait window - the bot starts and retries in the background.
     */
    private void awaitFirstResult() {

        long deadline = System.currentTimeMillis() + STARTUP_WAIT_MILLIS;

        synchronized (startLock) {
            while (!firstResult && System.currentTimeMillis() < deadline) {
                try {
                    startLock.wait(Math.max(50, deadline - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

    }

    private void markFirstResult() {
        synchronized (startLock) {
            firstResult = true;
            startLock.notifyAll();
        }
    }

    /**
     * Registering long polling with infinite backoff for network errors:
     * api.telegram.org may be unreachable at start (blocking, the proxy is not up yet),
     * but that is no reason to kill the whole bot. Fatal API errors (invalid token,
     * bot deleted) and a broken proxy configuration stop the loop instead.
     *
     * <p>{@code registerBot} is the start, not a preparation for one: telegrambots 10.3.0
     * marks the application running in its constructor and begins the session inside
     * {@code registerBot}, so a following {@code start()} always throws
     * "App is already running". Calling it anyway made every retry leak one more poller
     * with its own executor, and those pollers fought this process for the same token.
     */
    private void registerWithRetry(PlatformContext context, UpdateSink sink) {

        long backoffMillis = RETRY_INITIAL_MILLIS;

        while (running.get()) {

            Exception last = null;

            for (boolean viaProxy : routeOrder()) {

                routeViaProxy = viaProxy;
                TelegramBotsLongPollingApplication candidate = newApplication();

                try {

                    candidate.registerBot(token, this::telegramUrl, TelegramAdapter::getUpdates,
                            (LongPollingUpdateConsumer) new Consumer(sink, context));
                    application = candidate;
                    client = null;
                    preferDirect = proxy != null && !viaProxy;
                    markFirstResult();
                    registerMenuButton();
                    context.health().beat(Platform.TELEGRAM);

                    log.info("Telegram: long polling started{}", startedSuffix(viaProxy));

                    return;

                } catch (Exception e) {

                    closeQuietly(candidate);

                    if (isProxyFault(e)) {

                        running.set(false);
                        fatalStartup = new PlatformException("Telegram: " + rootMessage(e), e);
                        markFirstResult();

                        log.error("Telegram: {} - fix TELEGRAM_PROXY, retries will not help",
                                rootMessage(e));

                        return;

                    }

                    if (isFatalApiError(e)) {

                        running.set(false);
                        fatalStartup = new PlatformException(
                                "Telegram: failed to start long polling", e);
                        markFirstResult();

                        log.error("Telegram: fatal registration error (check the token) - {}",
                                rootMessage(e));

                        return;

                    }

                    last = e;

                    if (proxy != null) {

                        log.warn("Telegram: the {} route failed ({}); trying the {} route",
                                viaProxy ? "proxy" : "direct", rootMessage(e),
                                viaProxy ? "direct" : "proxy");

                        continue;

                    }

                    break;

                }
            }

            String reason = rootMessage(last);

            if (isPollingConflict(reason)) {
                log.warn("Telegram: token already used by another bot instance ({}), "
                        + "retry in {} s. Close the second process: two pollers "
                        + "with the same token intercept each other's updates",
                        reason, backoffMillis / 1000);
            } else if (proxy != null) {
                log.warn("Telegram: api.telegram.org unreachable via the proxy {} and directly "
                        + "({}), retry in {} s", proxy.label(), reason, backoffMillis / 1000);
            } else {
                log.warn("Telegram: api.telegram.org unreachable ({}), retry in {} s. "
                        + "If the network blocks Telegram - set a proxy: "
                        + "platforms.telegram.proxy or TELEGRAM_PROXY in config/.env",
                        reason, backoffMillis / 1000);
            }

            markFirstResult();
            backoffMillis = sleep(backoffMillis);

        }

    }

    /**
     * The routes to try in one registration round. With a proxy configured both are alive:
     * a proxy that refuses the CONNECT (a ruleset answer, a dead upstream) no longer buries
     * a network where Telegram is reachable directly, and a blocked network still gets
     * its proxy first. The route that won the last round goes first in the next one.
     */
    private List<Boolean> routeOrder() {

        if (proxy == null) {
            return List.of(Boolean.FALSE);
        }

        return preferDirect
                ? List.of(Boolean.FALSE, Boolean.TRUE)
                : List.of(Boolean.TRUE, Boolean.FALSE);

    }

    private String startedSuffix(boolean viaProxy) {

        if (proxy == null) {
            return "";
        }

        return viaProxy
                ? " (via proxy " + proxy.label() + ")"
                : " (directly: the configured proxy " + proxy.label()
                        + " cannot reach Telegram from this network)";

    }

    private long sleep(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            running.set(false);

        }

        return Math.min(millis * 2, RETRY_MAX_MILLIS);

    }

    private TelegramBotsLongPollingApplication newApplication() {

        if (proxy == null || !routeViaProxy) {
            return new TelegramBotsLongPollingApplication();
        }

        return new TelegramBotsLongPollingApplication(ObjectMapper::new, clientCreator());

    }

    /**
     * One client factory for both paths - long polling and outbound calls have to leave
     * through the same route, otherwise updates arrive but answers do not.
     */
    private Supplier<OkHttpClient> clientCreator() {

        ProxySpec spec = routeViaProxy ? proxy : null;

        if (spec == null) {
            return new TelegramOkHttpClientFactory.DefaultOkHttpClientCreator();
        }

        if (spec.proxy().type() == Proxy.Type.SOCKS) {
            InetSocketAddress address = (InetSocketAddress) spec.proxy().address();

            return new Socks5ClientCreator(
                    () -> new Socks5SocketFactory(address, spec.username(), spec.password()));
        }

        return new TelegramOkHttpClientFactory.HttpProxyOkHttpClientCreator(
                spec::proxy, this::proxyAuthenticator);

    }

    private TelegramUrl telegramUrl() {
        return apiUrl != null ? apiUrl : TelegramUrl.DEFAULT_URL;
    }

    /** getUpdates generator from the library: long polling limits/timeouts as in the default path. */
    private static final DefaultGetUpdatesGenerator GET_UPDATES = new DefaultGetUpdatesGenerator();

    private static GetUpdates getUpdates(Integer offset) {
        return GET_UPDATES.apply(offset);
    }

    /** Outbound client with a proxy or {@code null} for the direct route (library default). */
    private @Nullable OkHttpClient proxyHttpClient() {
        return proxy == null || !routeViaProxy ? null : clientCreator().get();
    }

    /** HTTP proxy Authenticator: supplies credentials from {@code user:pass@host:port} on 407. */
    private Authenticator proxyAuthenticator() {
        return (route, response) -> proxy != null && proxy.credentials() != null
                ? response.request().newBuilder()
                        .header("Proxy-Authorization", proxy.credentials())
                        .build()
                : null;
    }

    /** Mini App in the Telegram menu button - promoting the web app without its own site. */
    private void registerMenuButton() {

        if (webAppUrl == null) {
            return;
        }

        try {

            WebAppInfo webApp = WebAppInfo.builder().url(webAppUrl).build();
            MenuButtonWebApp menuButton = MenuButtonWebApp.builder()
                    .text(webAppTitle)
                    .webAppInfo(webApp)
                    .build();

            client.execute(SetChatMenuButton.builder().menuButton(menuButton).build());
            log.info("Telegram: menu_button set to {}", webAppUrl);

        } catch (TelegramApiException e) {
            log.warn("Telegram: failed to set menu_button: {}", e.getMessage());
        }

    }

    /** A fatal background-connection error (invalid token, etc.) or {@code null}. */
    @Override
    public @Nullable PlatformException fatalStartupError() {
        return fatalStartup;
    }

    @Override
    public boolean connected() {
        TelegramBotsLongPollingApplication current = application;
        return current != null && current.isRunning();
    }

    @Override
    public void stop() {

        running.set(false);

        Thread starter = retryThread;

        if (starter != null) {
            starter.interrupt();
        }

        if (application != null) {
            try {
                application.close();
            } catch (Exception e) {
                log.debug("Telegram: error stopping long polling", e);
            }
        }

    }

    private static void closeQuietly(TelegramBotsLongPollingApplication candidate) {
        try {
            candidate.close();
        } catch (Exception ignored) {
            // closing an already failed session - suppress the secondary error
        }
    }

    /**
     * A SOCKS5 handshake fault that only a config change fixes: a rejected login or a server
     * that does not speak SOCKS5. Both the type and the text marker are checked, because
     * wrappers may flatten the cause. Unlike a transient ruleset refusal, this stops startup:
     * silently falling back to a direct connection here would defeat a proxy that the
     * deployment actually needs.
     */
    static boolean isProxyFault(Throwable e) {

        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof Socks5FaultException
                    || t.getMessage() != null && t.getMessage().contains(Socks5FaultException.MARKER)) {
                return true;
            }
        }

        return false;

    }

    /**
     * Fatal = a deliberate 4xx API response (except 429): retries will not help.
     * Long polling wraps errors in {@link TelegramApiErrorResponseException}
     * (not TelegramApiRequestException) - the response code lives in its message.
     */
    static boolean isFatalApiError(Throwable e) {

        for (Throwable t = e; t != null; t = t.getCause()) {

            if (t instanceof TelegramApiRequestException request) {
                Integer code = request.getErrorCode();
                return code != null && code >= 400 && code < 500 && code != 429;
            }

            if (t instanceof TelegramApiErrorResponseException response) {
                java.util.OptionalInt code = errorCodeOf(response.toString());
                return code.isPresent()
                        && code.getAsInt() >= 400 && code.getAsInt() < 500
                        && code.getAsInt() != 429;
            }

        }

        return false;

    }

    /**
     * Long polling conflict: Telegram answers 409 "terminated by other getUpdates request" when
     * the same token is already polled by another process. Retries will not help while the second
     * instance is alive, so the reason is reported separately from an unreachable network.
     */
    static boolean isPollingConflict(String reason) {
        return reason != null && reason.contains("terminated by other getUpdates");
    }

    /** Response code from toString(): the library prints it as the first characters of the line ({@code 401: ...}, network = {@code -1:}). */
    private static java.util.OptionalInt errorCodeOf(String message) {

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^([1-5][0-9]{2}):")
                .matcher(message);

        return matcher.find()
                ? java.util.OptionalInt.of(Integer.parseInt(matcher.group(1)))
                : java.util.OptionalInt.empty();

    }

    private static String rootMessage(Throwable e) {

        Throwable root = e;

        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return message != null && !message.isBlank() ? message : String.valueOf(root);

    }

    private synchronized void ensureClient() {

        if (client == null) {

            OkHttpClient httpClient = proxyHttpClient();
            client = httpClient == null
                    ? new OkHttpTelegramClient(token, telegramUrl())
                    : new OkHttpTelegramClient(httpClient, token, telegramUrl());

            if (proxy != null) {

                if (routeViaProxy) {

                    log.info("Telegram: traffic via proxy {}", proxy.describe());

                    if (!proxy.schemeGiven()) {
                        log.warn("Telegram: TELEGRAM_PROXY carries no scheme - {} is assumed; "
                                + "write http:// or socks5:// explicitly to be sure", proxy.label());
                    }

                } else {
                    log.info("Telegram: traffic direct - the configured proxy {} lost "
                            + "to the direct connection", proxy.label());
                }

            }

        }

    }

    /**
     * Parsing {@code platforms.telegram.proxy}. Accepted forms:
     *
     * <ul>
     *   <li>{@code [http://|socks5://]host:port};</li>
     *   <li>{@code [http://]user:pass@host:port} - basic authorization of an HTTP proxy;</li>
     *   <li>{@code [socks5://]host:port:user:pass} - the form proxy sellers print in their
     *       lists, a SOCKS5 login (RFC 1929);</li>
     *   <li>{@code socks5://user:pass@host:port} - the same login in URL form.</li>
     * </ul>
     *
     * <p>Without a scheme a bare {@code host:port} is HTTP, as it has always been, and
     * {@code host:port:user:pass} is SOCKS5 - that colon form is how SOCKS lists are published.
     * The effective protocol is printed at startup either way.
     *
     * <p>An IPv6 literal stays bracketed: {@code socks5://[2001:db8::1]:1080:user:pass}.
     * A doubled port ({@code host:port:port}) is rejected: silently connecting to a half-parsed
     * address would look like a dead proxy, not a typo.
     */
    static @Nullable ProxySpec parseProxy(@Nullable String spec) {

        if (spec == null || spec.isBlank()) {
            return null;
        }

        String value = spec.trim();
        Proxy.Type declared = null;
        String lower = value.toLowerCase(Locale.ROOT);

        if (lower.startsWith("socks5://") || lower.startsWith("socks://")) {
            declared = Proxy.Type.SOCKS;
            value = value.substring(value.indexOf("://") + 3);
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            declared = Proxy.Type.HTTP;
            value = value.substring(value.indexOf("://") + 3);
        }

        String user = null;
        String password = null;
        int at = value.lastIndexOf('@');

        if (at >= 0) {
            String credentials = value.substring(0, at);
            int colon = credentials.indexOf(':');
            user = colon >= 0 ? credentials.substring(0, colon) : credentials;
            password = colon >= 0 ? credentials.substring(colon + 1) : "";
            value = value.substring(at + 1);
        }

        List<String> parts = splitOutsideBrackets(value);
        String host;
        String portText;

        if (parts.size() == 2) {
            host = parts.get(0);
            portText = parts.get(1);
        } else if (parts.size() == 4 && user == null) {

            host = parts.get(0);
            portText = parts.get(1);
            user = parts.get(2);
            password = parts.get(3);

        } else if (parts.size() == 3) {
            throw malformed(spec, "the host part holds one host and one port, "
                    + "a second ':port' suffix or a login without a password "
                    + "is a configuration mistake");
        } else {
            throw malformed(spec, parts.size() == 4
                    ? "credentials are given twice - pick either user:pass@host:port "
                      + "or host:port:user:pass"
                    : "expected host:port or host:port:user:pass; if the password contains ':', "
                      + "write it as [socks5://]user:pass@host:port");
        }

        if (host.isEmpty()) {
            throw malformed(spec, "no host before the port");
        }

        if (user != null && (user.isBlank() || password == null || password.isBlank())) {
            throw malformed(spec, "the login or the password is empty; an empty password "
                    + "is only possible in the user:@host:port form");
        }

        if (user != null && exceedsSocksLimit(user, password)) {
            throw malformed(spec, "a SOCKS5 login and password are limited to 255 bytes each");
        }

        Proxy.Type type = declared != null
                ? declared
                : (parts.size() == 4 ? Proxy.Type.SOCKS : Proxy.Type.HTTP);

        int port = parsePort(portText, spec);
        Proxy proxy = new Proxy(type, new InetSocketAddress(host, port));
        String label = (type == Proxy.Type.SOCKS ? "socks5://" : "http://") + host + ":" + port;

        return new ProxySpec(proxy, user, password, label, declared != null);

    }

    private static ConfigException malformed(String spec, String reason) {
        return new ConfigException("platforms.telegram.proxy",
                reason + " - expected format [http://|socks5://]host:port[:user:pass], got '"
                        + spec + "'");
    }

    private static int parsePort(String text, String spec) {

        int port;

        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw malformed(spec, "invalid port");
        }

        if (port < 1 || port > 65_535) {
            throw malformed(spec, "port out of range");
        }

        return port;

    }

    private static boolean exceedsSocksLimit(String user, @Nullable String password) {
        return user.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255
                || password != null && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255;
    }

    /**
     * Splits on ':' outside a bracketed IPv6 literal, so {@code [::1]:1080} is a host and a port.
     * Colons inside brackets belong to the address, everything after it - to port and login.
     */
    private static List<String> splitOutsideBrackets(String value) {

        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ':' && depth == 0) {
                parts.add(value.substring(start, i).trim());
                start = i + 1;
            }

        }

        parts.add(value.substring(start).trim());
        return parts;

    }

    /**
     * A parsed proxy: the address and its type, an optional SOCKS5 or HTTP login
     * and a log label. The password is never part of {@link #label()} or {@link #describe()}.
     */
    record ProxySpec(Proxy proxy, @Nullable String username, @Nullable String password,
                     String label, boolean schemeGiven) {
        /** A ready Proxy-Authorization header for an HTTP proxy, or {@code null}. */
        @Nullable String credentials() {
            return username == null ? null : Credentials.basic(username, password == null ? "" : password);
        }

        /** The address plus a note about the login - for a startup line, without the password. */
        String describe() {
            return username == null ? label : label + " (user " + username + ")";
        }

        /** Overridden: the generated record toString would print the password into any log. */
        @Override
        public String toString() {
            return "ProxySpec[" + describe() + "]";
        }
    }

    @Override
    public SendResult execute(OutboundMessage message) {

        ensureClient();

        try {
            return switch (message) {
                case OutboundMessage.Send send -> messageId(client.execute(sendMessage(send)));
                case OutboundMessage.SendMedia media -> sendMedia(media);
                case OutboundMessage.SendMediaGroup group -> sendMediaGroup(group);
                case OutboundMessage.Edit edit -> {
                    client.execute(editMessage(edit));
                    yield SendResult.of(edit.messageId());
                }
                case OutboundMessage.Delete delete -> {
                    client.execute(deleteMessage(delete));
                    yield SendResult.ack();
                }
                case OutboundMessage.AnswerCallback answer -> {
                    client.execute(answerCallback(answer));
                    yield SendResult.ack();
                }
            };
        } catch (TelegramApiException e) {

            if (isNoOp(e)) {

                log.debug("Telegram: {} needs no action: {}",
                        message.getClass().getSimpleName(), rootMessage(e));
                return SendResult.ack();
            }
            throw classify(e);
        }

    }

    private static SendResult messageId(Message message) {
        return message != null && message.getMessageId() != null
                ? SendResult.of(String.valueOf(message.getMessageId()))
                : SendResult.ack();
    }

    /**
     * Media by native type: a photo stays a photo (inline preview), a video stays a
     * video (player + streaming), and anything Telegram has no native type for goes
     * as a document. The caption limit is 1024 chars - the renderer output is cut,
     * not rejected: a long title must not lose the user their file.
     */
    private SendResult sendMedia(OutboundMessage.SendMedia send) throws TelegramApiException {

        MediaAttachment media = send.media();

        java.io.File file = media.file().toFile();

        if (!file.isFile()) {
            throw new PlatformException.PermanentDeliveryException(
                    "Telegram: media file vanished before the send: " + media.file());
        }

        long chatId = TelegramUpdateMapper.nativeChatId(send.chatId());
        String caption = captionOf(send.caption());
        var keyboard = TelegramKeyboardMapper.map(send.keyboard());

        return switch (media.kind()) {

            case PHOTO -> messageId(client.execute(SendPhoto.builder()
                    .chatId(chatId)
                    .photo(new InputFile(file))
                    .caption(caption)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(keyboard)
                    .disableNotification(send.silent())
                    .build()));

            case VIDEO -> {
                SendVideo.SendVideoBuilder<?, ?> builder = SendVideo.builder()
                        .chatId(chatId)
                        .video(new InputFile(file))
                        .caption(caption)
                        .parseMode(ParseMode.HTML)
                        .replyMarkup(keyboard)
                        .supportsStreaming(true)
                        .disableNotification(send.silent());
                if (media.durationSeconds() != null) builder.duration(media.durationSeconds());
                if (media.width() != null) builder.width(media.width());
                if (media.height() != null) builder.height(media.height());
                yield messageId(client.execute(builder.build()));
            }

            case AUDIO -> {
                SendAudio.SendAudioBuilder<?, ?> builder = SendAudio.builder()
                        .chatId(chatId)
                        .audio(new InputFile(file))
                        .caption(caption)
                        .parseMode(ParseMode.HTML)
                        .replyMarkup(keyboard)
                        .disableNotification(send.silent());
                if (media.durationSeconds() != null) builder.duration(media.durationSeconds());
                if (media.title() != null) builder.title(media.title());
                yield messageId(client.execute(builder.build()));
            }

            case GIF -> {
                SendAnimation.SendAnimationBuilder<?, ?> builder = SendAnimation.builder()
                        .chatId(chatId)
                        .animation(new InputFile(file))
                        .caption(caption)
                        .parseMode(ParseMode.HTML)
                        .replyMarkup(keyboard)
                        .disableNotification(send.silent());
                if (media.durationSeconds() != null) builder.duration(media.durationSeconds());
                if (media.width() != null) builder.width(media.width());
                if (media.height() != null) builder.height(media.height());
                yield messageId(client.execute(builder.build()));
            }

            case DOCUMENT -> messageId(client.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(file))
                    .caption(caption)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(keyboard)
                    .disableNotification(send.silent())
                    .build()));

        };

    }

    /**
     * Caption within Telegram's 1024-character limit. The cut happens on the RichText
     * BEFORE rendering: cutting the rendered HTML would slice tags in half (a 400 from
     * the API) and miscount, because Telegram measures the TEXT, not the markup.
     * Empty captions become null - Telegram rejects "" on media.
     */
    private static String captionOf(RichText caption) {

        String rendered = TelegramHtmlRenderer.render(caption.truncate(1024)).trim();

        return rendered.isEmpty() ? null : rendered;

    }

    /**
     * A native album: up to 10 files, one message. The caption rides on the first item
     * (Telegram shows it under the whole group); kinds map to the matching InputMedia
     * type so videos keep their player and photos their grid preview.
     */
    private SendResult sendMediaGroup(OutboundMessage.SendMediaGroup send)
            throws TelegramApiException {

        java.util.List<InputMedia> medias = new java.util.ArrayList<>(send.media().size());
        String caption = captionOf(send.caption());

        for (eu.neydev.saver.core.media.MediaAttachment media : send.media()) {

            java.io.File file = media.file().toFile();

            if (!file.isFile()) {
                throw new PlatformException.PermanentDeliveryException(
                        "Telegram: media file vanished before the send: " + media.file());
            }

            // v10 API: media(File, attachName) marks the item as a NEW upload and
            // wires the multipart attach-name; the String-only overload is for
            // file_ids and URLs.
            InputMedia item = switch (media.kind()) {

                case PHOTO -> InputMediaPhoto.builder()
                        .media(file, media.fileName())
                        .build();

                case VIDEO -> {
                    InputMediaVideo.InputMediaVideoBuilder<?, ?> builder = InputMediaVideo.builder()
                            .media(file, media.fileName());
                    if (media.durationSeconds() != null) builder.duration(media.durationSeconds());
                    if (media.width() != null) builder.width(media.width());
                    if (media.height() != null) builder.height(media.height());
                    yield builder.build();
                }

                case GIF -> {
                    InputMediaAnimation.InputMediaAnimationBuilder<?, ?> builder =
                            InputMediaAnimation.builder().media(file, media.fileName());
                    if (media.durationSeconds() != null) builder.duration(media.durationSeconds());
                    yield builder.build();
                }

                case AUDIO -> InputMediaAudio.builder()
                        .media(file, media.fileName())
                        .build();

                case DOCUMENT -> InputMediaDocument.builder()
                        .media(file, media.fileName())
                        .build();

            };

            medias.add(item);

        }

        if (caption != null) {
            medias.get(0).setCaption(caption);
            medias.get(0).setParseMode(ParseMode.HTML);
        }

        java.util.List<Message> sent = client.execute(SendMediaGroup.builder()
                .chatId(TelegramUpdateMapper.nativeChatId(send.chatId()))
                .medias(medias)
                .disableNotification(send.silent())
                .build());

        return sent != null && !sent.isEmpty() && sent.get(0).getMessageId() != null
                ? SendResult.of(String.valueOf(sent.get(0).getMessageId()))
                : SendResult.ack();

    }

    /**
     * Two of Telegram's 400s report "nothing to do", not "something failed": an edit
     * whose new content equals the current one (a button pressed twice on the same
     * screen), and a toast for a callback whose thirty-second answer window closed
     * while the bot was restarting. Neither ever succeeds on a retry, so neither may
     * enter the retry ladder: before this, one doubled button press filled the log
     * with six WARNs and occupied an outbound worker for three minutes.
     */
    static boolean isNoOp(TelegramApiException e) {

        if (!(e instanceof TelegramApiRequestException request)) {
            return false;
        }

        String description = String.valueOf(request.getApiResponse()).toLowerCase(Locale.ROOT);

        return description.contains("message is not modified")
                || description.contains("query is too old")
                || description.contains("query id is invalid");

    }

    private SendMessage sendMessage(OutboundMessage.Send send) {
        return SendMessage.builder()
                .chatId(TelegramUpdateMapper.nativeChatId(send.chatId()))
                .text(TelegramHtmlRenderer.render(send.text()))
                .parseMode(ParseMode.HTML)
                .replyMarkup(TelegramKeyboardMapper.map(send.keyboard()))
                .disableNotification(send.silent())
                .build();
    }

    private EditMessageText editMessage(OutboundMessage.Edit edit) {
        return EditMessageText.builder()
                .chatId(TelegramUpdateMapper.nativeChatId(edit.chatId()))
                .messageId(TelegramUpdateMapper.nativeMessageId(edit.messageId()))
                .text(TelegramHtmlRenderer.render(edit.text()))
                .parseMode(ParseMode.HTML)
                .replyMarkup(TelegramKeyboardMapper.map(edit.keyboard()))
                .build();
    }

    private DeleteMessage deleteMessage(OutboundMessage.Delete delete) {
        return DeleteMessage.builder()
                .chatId(TelegramUpdateMapper.nativeChatId(delete.chatId()))
                .messageId(TelegramUpdateMapper.nativeMessageId(delete.messageId()))
                .build();
    }

    private AnswerCallbackQuery answerCallback(OutboundMessage.AnswerCallback answer) {

        AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> builder = AnswerCallbackQuery.builder()
                .callbackQueryId(answer.interactionId());

        if (!answer.text().isEmpty()) {
            builder.text(answer.text()).showAlert(answer.showAlert());
        }

        return builder.build();

    }

    private RuntimeException classify(TelegramApiException e) {

        if (isProxyFault(e)) {
            return new PlatformException("Telegram: " + rootMessage(e), e);
        }

        if (e instanceof TelegramApiRequestException requestException) {

            Integer code = requestException.getErrorCode();

            if (code != null && code == 429) {

                long retryAfter = 3_000;

                if (requestException.getParameters() != null
                        && requestException.getParameters().getRetryAfter() != null) {
                    retryAfter = requestException.getParameters().getRetryAfter() * 1000L;
                }

                return new PlatformException.RateLimitedException("Telegram 429", retryAfter);

            }

            String description = String.valueOf(requestException.getApiResponse()).toLowerCase(Locale.ROOT);
            boolean permanent = code != null && (code == 403 || code == 404)
                    && (description.contains("blocked") || description.contains("kicked")
                    || description.contains("chat not found") || description.contains("deactivated")
                    || description.contains("user is disabled"));

            if (permanent) {
                return new PlatformException.PermanentDeliveryException("Telegram: " + description);
            }

        }

        return new PlatformException("Telegram API: " + e.getMessage(), e);

    }

    /** Update consumer: mapping + handoff to the pipeline; one update failing does not break the session. */
    private record Consumer(UpdateSink sink, PlatformContext context) implements LongPollingUpdateConsumer {

        /**
         * Overridden without checked exceptions: the interface's default close()
         * declares throws Exception, which made the javac lint [try] warn
         * about a potential InterruptedException in an auto-closeable resource.
         */
        @Override
        public void close() {
        }

        @Override
        public void consume(List<Update> updates) {

            context.health().beat(Platform.TELEGRAM);

            for (Update update : updates) {
                try {
                    TelegramUpdateMapper.map(update).ifPresent(sink::accept);
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger(Consumer.class)
                            .warn("Telegram: could not map update {}", update.getUpdateId(), e);
                }
            }

        }

    }

}

