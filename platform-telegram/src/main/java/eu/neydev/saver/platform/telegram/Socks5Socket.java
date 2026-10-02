package eu.neydev.saver.platform.telegram;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * A socket whose {@code connect} opens a SOCKS5 tunnel and then behaves as an ordinary
 * connected socket: method negotiation, an optional RFC 1929 login and CONNECT happen once,
 * everything after them is delegated to the tunnel.
 *
 * <p>The target is sent to the proxy as a domain name whenever one is known, so DNS is resolved
 * on the proxy side - local resolution may be poisoned exactly where a proxy is needed.
 * An address that is already an IP literal goes as raw bytes.
 *
 * <p>Faults are split in two: a configuration problem (a rejected login, a server that is not
 * SOCKS5, a ruleset refusal) becomes {@link Socks5FaultException} and stops the retries,
 * everything transient (an unreachable network, a refused target) stays a plain
 * {@link SocketException} and is retried by the caller.
 */
final class Socks5Socket extends Socket {

    private static final int VERSION = 0x05;
    private static final int METHOD_NO_AUTH = 0x00;
    private static final int METHOD_USER_PASS = 0x02;
    private static final int METHOD_NONE_ACCEPTABLE = 0xff;
    private static final int AUTH_VERSION = 0x01;
    private static final int COMMAND_CONNECT = 0x01;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;
    private static final int MAX_FIELD_BYTES = 255;

    /** Reply codes that a retry cannot fix: the ruleset, the command or the address type. */
    private static final Set<Integer> FATAL_REPLIES = Set.of(0x02, 0x07, 0x08);

    /** An upper bound for the handshake reads: a proxy that accepts TCP but never answers
     * must not hold the caller for the whole connect timeout. */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 20_000;

    private final InetSocketAddress proxy;
    private final @Nullable byte[] username;
    private final @Nullable byte[] password;

    private @Nullable Socket tunnel;
    private @Nullable InetSocketAddress target;
    private @Nullable SocketAddress bindAddress;
    private int readTimeoutMillis;
    private boolean tcpNoDelay;
    private boolean keepAlive;
    private boolean reuseAddress = true;
    private int receiveBuffer = -1;
    private int sendBuffer = -1;
    private int soLinger = -1;
    private int trafficClass;
    private boolean closed;

    Socks5Socket(InetSocketAddress proxy, @Nullable String username, @Nullable String password) {
        this.proxy = proxy;
        this.username = credential(username, "user");
        this.password = credential(password, "password");
    }

    private static @Nullable byte[] credential(@Nullable String value, String what) {

        if (value == null) {
            return null;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_FIELD_BYTES) {
            throw new IllegalArgumentException("the SOCKS5 " + what + " is longer than "
                    + MAX_FIELD_BYTES + " bytes");
        }

        return bytes;

    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeoutMillis) throws IOException {

        if (tunnel != null) {
            throw new SocketException("already connected");
        }

        if (closed) {
            throw new SocketException("socket is closed");
        }

        if (!(endpoint instanceof InetSocketAddress destination)) {
            throw new SocketException("unsupported address type: " + endpoint);
        }

        Socket socket = new Socket();

        try {

            if (bindAddress != null) {
                socket.bind(bindAddress);
            }

            socket.connect(proxy, timeoutMillis);
            socket.setSoTimeout(handshakeTimeout(timeoutMillis));

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            negotiate(in, out);
            requestConnect(in, out, destination);

            socket.setSoTimeout(readTimeoutMillis);
            applyOptions(socket);

        } catch (IOException | RuntimeException e) {

            // the handshake cannot be repeated on this instance - it is dead, not "not yet connected"
            closed = true;
            closeQuietly(socket);
            throw e;

        }

        tunnel = socket;
        target = destination;

    }

    private static int handshakeTimeout(int timeoutMillis) {
        return timeoutMillis > 0
                ? Math.min(timeoutMillis, HANDSHAKE_TIMEOUT_MILLIS)
                : HANDSHAKE_TIMEOUT_MILLIS;
    }

    /** Offers "no auth" and "user/password": an open proxy stays usable with a login configured. */
    private void negotiate(DataInputStream in, OutputStream out) throws IOException {

        out.write(new byte[] {VERSION, 2, METHOD_NO_AUTH, METHOD_USER_PASS});
        out.flush();

        int version = read(in, "protocol version");

        if (version != VERSION) {
            throw new Socks5FaultException(fault("is not a SOCKS5 server (protocol byte 0x%02x) - "
                    .formatted(version)
                    + "if this is an HTTP proxy, write it as http://host:port[:user:pass]"));
        }

        int method = read(in, "authentication method");

        if (method == METHOD_NONE_ACCEPTABLE) {
            throw new Socks5FaultException(fault("accepts none of the offered methods "
                    + "(no authentication, username/password)"));
        }

        if (method == METHOD_USER_PASS) {
            authenticate(in, out);
        } else if (method != METHOD_NO_AUTH) {
            throw new Socks5FaultException(fault("requires an unsupported authentication method 0x%02x"
                    .formatted(method)));
        }

    }

    private void authenticate(DataInputStream in, OutputStream out) throws IOException {

        if (username == null || password == null) {
            throw new Socks5FaultException(fault("requires a login, but none is configured - "
                    + "write it as socks5://host:port:user:pass or socks5://user:pass@host:port"));
        }

        out.write(AUTH_VERSION);
        out.write(username.length);
        out.write(username);
        out.write(password.length);
        out.write(password);
        out.flush();

        read(in, "authentication reply version");
        int status = read(in, "authentication status");

        if (status != 0) {
            throw new Socks5FaultException(fault("rejected user '%s' (status 0x%02x) - "
                    .formatted(new String(username, StandardCharsets.UTF_8), status)
                    + "check the login and the password in TELEGRAM_PROXY, and whether "
                    + "the proxy is bound to this IP address"));
        }

    }

    private void requestConnect(DataInputStream in, OutputStream out, InetSocketAddress destination)
            throws IOException {

        out.write(VERSION);
        out.write(COMMAND_CONNECT);
        out.write(0);
        writeAddress(out, destination);
        out.write(destination.getPort() >>> 8 & 0xff);
        out.write(destination.getPort() & 0xff);
        out.flush();

        int version = read(in, "reply version");

        if (version != VERSION) {
            throw new Socks5FaultException(fault("answered the CONNECT with protocol byte 0x%02x"
                    .formatted(version)));
        }

        int reply = read(in, "reply code");

        read(in, "reserved byte");
        skipBoundAddress(in);

        if (reply != 0) {

            String reason = "refused CONNECT to %s: %s"
                    .formatted(label(destination), replyText(reply));

            if (FATAL_REPLIES.contains(reply)) {
                throw new Socks5FaultException(fault(reason));
            }

            throw new SocketException(description() + " " + reason);

        }
    }

    private void writeAddress(OutputStream out, InetSocketAddress destination) throws IOException {

        String host = destination.getHostString();

        if (host == null || isIpLiteral(host)) {

            InetAddress address = destination.getAddress();

            if (address == null) {
                throw new SocketException("no address to connect to: " + destination);
            }

            out.write(address instanceof Inet6Address ? ATYP_IPV6 : ATYP_IPV4);
            out.write(address.getAddress());

            return;

        }

        byte[] name = host.getBytes(StandardCharsets.UTF_8);

        if (name.length > MAX_FIELD_BYTES) {
            throw new SocketException("host name too long for SOCKS5: " + host);
        }

        out.write(ATYP_DOMAIN);
        out.write(name.length);
        out.write(name);

    }

    /** The bound address is not used by the caller, but it has to be read to keep the stream aligned. */
    private void skipBoundAddress(DataInputStream in) throws IOException {

        int type = read(in, "address type");

        switch (type) {
            case ATYP_IPV4 -> in.readFully(new byte[4]);
            case ATYP_IPV6 -> in.readFully(new byte[16]);
            case ATYP_DOMAIN -> in.readFully(new byte[read(in, "domain length")]);
            default -> throw new Socks5FaultException(fault("sent an unknown address type 0x%02x"
                    .formatted(type)));
        }

        in.readFully(new byte[2]);

    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(c -> c == '.' || c >= '0' && c <= '9');
    }

    private static String replyText(int reply) {
        return switch (reply) {
            case 0x01 -> "general server failure";
            case 0x02 -> "connection not allowed by ruleset (a login, a subscription "
                    + "or an IP whitelist problem)";
            case 0x03 -> "network unreachable";
            case 0x04 -> "host unreachable";
            case 0x05 -> "connection refused by the target";
            case 0x06 -> "TTL expired";
            case 0x07 -> "command not supported";
            case 0x08 -> "address type not supported";
            default -> "reply code 0x%02x".formatted(reply);
        };
    }

    private int read(DataInputStream in, String what) throws IOException {

        int value = in.read();

        if (value < 0) {
            throw new SocketException("%s closed the connection while reading the %s"
                    .formatted(description(), what));
        }

        return value;

    }

    private void applyOptions(Socket socket) throws SocketException {

        socket.setTcpNoDelay(tcpNoDelay);
        socket.setKeepAlive(keepAlive);
        socket.setReuseAddress(reuseAddress);

        if (receiveBuffer > 0) {
            socket.setReceiveBufferSize(receiveBuffer);
        }

        if (sendBuffer > 0) {
            socket.setSendBufferSize(sendBuffer);
        }

        if (soLinger >= 0) {
            socket.setSoLinger(true, soLinger);
        }

        if (trafficClass != 0) {
            socket.setTrafficClass(trafficClass);
        }

    }

    private Socket tunnel() throws SocketException {

        Socket current = tunnel;

        if (current == null || closed) {
            throw new SocketException("socket is not connected");
        }

        return current;

    }

    /** The proxy in the URL form - for retryable errors, without the fatal-fault marker. */
    private String description() {
        return "socks5://" + address();
    }

    /** A fatal-fault message: it always starts with the marker the adapter looks for. */
    private String fault(String text) {
        return Socks5FaultException.MARKER + " " + address() + " " + text;
    }

    private String address() {
        return proxy.getHostString() + ":" + proxy.getPort();
    }

    private static String label(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // a socket that failed during the handshake - the original error is the important one
        }
    }

    @Override
    public void bind(SocketAddress bindpoint) throws IOException {

        Socket current = tunnel;

        if (current != null) {
            current.bind(bindpoint);
            return;
        }

        bindAddress = bindpoint;

    }

    @Override
    public InputStream getInputStream() throws IOException {
        return tunnel().getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return tunnel().getOutputStream();
    }

    @Override
    public @Nullable InetAddress getInetAddress() {
        return target != null ? target.getAddress() : null;
    }

    @Override
    public int getPort() {
        return target != null ? target.getPort() : 0;
    }

    @Override
    public @Nullable SocketAddress getRemoteSocketAddress() {
        return target;
    }

    @Override
    public InetAddress getLocalAddress() {
        Socket current = tunnel;
        return current != null ? current.getLocalAddress() : InetAddress.getLoopbackAddress();
    }

    @Override
    public int getLocalPort() {
        Socket current = tunnel;
        return current != null ? current.getLocalPort() : 0;
    }

    @Override
    public @Nullable SocketAddress getLocalSocketAddress() {
        Socket current = tunnel;
        return current != null ? current.getLocalSocketAddress() : null;
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {

        if (timeout < 0) {
            throw new IllegalArgumentException("timeout can't be negative");
        }

        readTimeoutMillis = timeout;

        Socket current = tunnel;

        if (current != null) {
            current.setSoTimeout(timeout);
        }

    }

    @Override
    public int getSoTimeout() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getSoTimeout() : readTimeoutMillis;
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {

        tcpNoDelay = on;

        Socket current = tunnel;

        if (current != null) {
            current.setTcpNoDelay(on);
        }

    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getTcpNoDelay() : tcpNoDelay;
    }

    @Override
    public void setKeepAlive(boolean on) throws SocketException {

        keepAlive = on;

        Socket current = tunnel;

        if (current != null) {
            current.setKeepAlive(on);
        }

    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getKeepAlive() : keepAlive;
    }

    @Override
    public void setReuseAddress(boolean on) throws SocketException {

        reuseAddress = on;

        Socket current = tunnel;

        if (current != null) {
            current.setReuseAddress(on);
        }

    }

    @Override
    public boolean getReuseAddress() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getReuseAddress() : reuseAddress;
    }

    @Override
    public void setReceiveBufferSize(int size) throws SocketException {

        if (size <= 0) {
            throw new IllegalArgumentException("receive buffer must be positive");
        }

        receiveBuffer = size;

        Socket current = tunnel;

        if (current != null) {
            current.setReceiveBufferSize(size);
        }

    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getReceiveBufferSize() : Math.max(receiveBuffer, 0);
    }

    @Override
    public void setSendBufferSize(int size) throws SocketException {

        if (size <= 0) {
            throw new IllegalArgumentException("send buffer must be positive");
        }

        sendBuffer = size;

        Socket current = tunnel;

        if (current != null) {
            current.setSendBufferSize(size);
        }

    }

    @Override
    public int getSendBufferSize() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getSendBufferSize() : Math.max(sendBuffer, 0);
    }

    @Override
    public void setSoLinger(boolean on, int linger) throws SocketException {

        soLinger = on ? Math.max(linger, 0) : -1;

        Socket current = tunnel;

        if (current != null) {
            current.setSoLinger(on, linger);
        }

    }

    @Override
    public int getSoLinger() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getSoLinger() : soLinger;
    }

    @Override
    public void setTrafficClass(int classValue) throws SocketException {

        trafficClass = classValue;

        Socket current = tunnel;

        if (current != null) {
            current.setTrafficClass(classValue);
        }

    }

    @Override
    public int getTrafficClass() throws SocketException {
        Socket current = tunnel;
        return current != null ? current.getTrafficClass() : trafficClass;
    }

    @Override
    public void sendUrgentData(int data) throws IOException {
        tunnel().sendUrgentData(data);
    }

    @Override
    public void shutdownInput() throws IOException {
        tunnel().shutdownInput();
    }

    @Override
    public void shutdownOutput() throws IOException {
        tunnel().shutdownOutput();
    }

    @Override
    public boolean isConnected() {
        return tunnel != null && !closed;
    }

    @Override
    public boolean isBound() {
        return tunnel != null || bindAddress != null;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean isInputShutdown() {
        Socket current = tunnel;
        return current != null && current.isInputShutdown();
    }

    @Override
    public boolean isOutputShutdown() {
        Socket current = tunnel;
        return current != null && current.isOutputShutdown();
    }

    @Override
    public void close() throws IOException {

        closed = true;

        Socket current = tunnel;

        if (current != null) {
            current.close();
        }

    }

    @Override
    public String toString() {
        return "Socks5Socket[" + description() + " -> "
                + (target == null ? "not connected" : label(target)) + "]";
    }

}

