package eu.neydev.saver.platform.telegram;

import org.jetbrains.annotations.Nullable;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * Sockets that reach the target through a SOCKS5 proxy, with a login when the proxy wants one.
 *
 * <p>{@code java.net.Proxy} of type SOCKS has no place for credentials: the JDK asks a global
 * {@link java.net.Authenticator} and quietly falls back to "user.name without a password",
 * so a proxy sold with a login answers "connection not allowed by ruleset" and looks dead.
 * This factory runs the RFC 1929 handshake itself and names the real reason on failure.
 *
 * <p>OkHttp gets {@link java.net.Proxy#NO_PROXY} plus this factory, so the tunnel is built
 * inside {@link Socket#connect} and the client believes it talks to the target directly.
 */
final class Socks5SocketFactory extends SocketFactory {

    private final InetSocketAddress proxy;
    private final @Nullable String username;
    private final @Nullable String password;

    /** {@code username} is {@code null} for an open proxy. */
    Socks5SocketFactory(InetSocketAddress proxy, @Nullable String username, @Nullable String password) {

        this.proxy = proxy;
        this.username = username;
        this.password = password;

    }

    @Override
    public Socket createSocket() {
        return new Socks5Socket(proxy, username, password);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connected(new InetSocketAddress(host, port), 0);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return connected(new InetSocketAddress(host, port),
                new InetSocketAddress(localAddress, localPort), 0);
    }

    @Override
    public Socket createSocket(InetAddress address, int port) throws IOException {
        return connected(new InetSocketAddress(address, port), 0);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return connected(new InetSocketAddress(address, port),
                new InetSocketAddress(localAddress, localPort), 0);
    }

    private Socket connected(SocketAddress target, int timeoutMillis) throws IOException {
        return connected(target, null, timeoutMillis);
    }

    private Socket connected(SocketAddress target, @Nullable SocketAddress bindAddress, int timeoutMillis)
            throws IOException {

        Socket socket = createSocket();

        if (bindAddress != null) {
            socket.bind(bindAddress);
        }

        socket.connect(target, timeoutMillis);

        return socket;

    }

}

