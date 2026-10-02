package eu.neydev.saver.platform.telegram;

import okhttp3.OkHttpClient;
import org.telegram.telegrambots.longpolling.util.TelegramOkHttpClientFactory;

import javax.net.SocketFactory;
import java.net.Proxy;
import java.util.function.Supplier;

/**
 * An OkHttp client whose sockets come from {@link Socks5SocketFactory}.
 *
 * <p>The library's own SOCKS creator hands the address to {@code java.net.Proxy}, which cannot
 * carry a login; here the proxy is {@link Proxy#NO_PROXY} and the tunnel is built by the socket
 * factory. Dispatcher, connection pool and timeouts are inherited from the library defaults,
 * so long polling keeps its 100 second read timeout.
 */
final class Socks5ClientCreator extends TelegramOkHttpClientFactory.DefaultOkHttpClientCreator {

    private final Supplier<SocketFactory> socketFactory;

    Socks5ClientCreator(Supplier<SocketFactory> socketFactory) {
        this.socketFactory = socketFactory;
    }

    @Override
    public OkHttpClient get() {
        return getBaseClient()
                .proxy(Proxy.NO_PROXY)
                .socketFactory(socketFactory.get())
                .build();
    }

}

