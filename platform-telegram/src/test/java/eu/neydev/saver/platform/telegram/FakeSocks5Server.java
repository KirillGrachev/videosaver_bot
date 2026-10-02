package eu.neydev.saver.platform.telegram;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A minimal SOCKS5 server for tests: it negotiates the method, checks the login when one is
 * configured, answers CONNECT and relays bytes to the real target. The modes replay the failures
 * a real proxy produces - a rejected login, a ruleset refusal, an HTTP server on the proxy port.
 */
final class FakeSocks5Server implements AutoCloseable {

    enum Mode {

        WORKING,
        REJECT_LOGIN,
        REFUSE_CONNECT,
        REFUSE_TARGET,
        NOT_SOCKS

    }

    private static final byte[] REPLY_OK = {5, 0, 0, 1, 0, 0, 0, 0, 0, 0};

    private final ServerSocket server;
    private final Mode mode;
    private final String user;
    private final String password;
    private final AtomicBoolean authChecked = new AtomicBoolean();
    private final AtomicBoolean authSucceeded = new AtomicBoolean();
    private final AtomicReference<String> connectedTo = new AtomicReference<>();
    private final AtomicInteger tunnels = new AtomicInteger();
    private volatile boolean closed;

    /** {@code user} is {@code null} for an open proxy. */
    FakeSocks5Server(Mode mode, String user, String password) throws IOException {

        this.mode = mode;
        this.user = user;
        this.password = password;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));

        Thread acceptor = new Thread(this::acceptLoop, "fake-socks5");

        acceptor.setDaemon(true);
        acceptor.start();

    }

    int port() {
        return server.getLocalPort();
    }

    boolean authChecked() {
        return authChecked.get();
    }

    boolean authSucceeded() {
        return authSucceeded.get();
    }

    String connectedTo() {
        return connectedTo.get();
    }

    /** A CONNECT request reached the proxy - whatever the answer was. */
    boolean connectAttempted() {
        return connectedTo.get() != null;
    }

    int tunnels() {
        return tunnels.get();
    }

    @Override
    public void close() {

        closed = true;

        try {
            server.close();
        } catch (IOException ignored) {
            // the sessions are daemon threads, the test does not wait for them
        }

    }

    private void acceptLoop() {

        while (!closed) {

            try {

                Socket client = server.accept();
                Thread session = new Thread(() -> serve(client), "fake-socks5-session");

                session.setDaemon(true);
                session.start();

            } catch (IOException e) {
                return;
            }

        }

    }

    private void serve(Socket client) {

        try (client) {

            DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            if (mode == Mode.NOT_SOCKS) {

                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                return;

            }

            if (!negotiate(in, out)) {
                return;
            }

            String target = readConnectRequest(in);

            if (target == null) {
                return;
            }

            connectedTo.set(target);

            byte reply = replyCode();
            Socket upstream = null;

            if (reply == 0) {
                try {
                    upstream = connectTarget(target);
                } catch (IOException e) {
                    reply = 5;
                }
            }

            out.write(replyBytes(reply));
            out.flush();

            if (upstream == null) {
                return;
            }

            tunnels.incrementAndGet();
            relay(client, upstream);

        } catch (IOException | RuntimeException ignored) {
            // the assertions live on the client side
        }

    }

    private boolean negotiate(DataInputStream in, OutputStream out) throws IOException {

        if (in.read() != 5) {
            return false;
        }

        int offered = in.read();
        byte[] methods = in.readNBytes(Math.max(offered, 0));
        boolean supportsLogin = false;

        for (byte method : methods) {
            supportsLogin |= method == 2;
        }

        if (user == null || !supportsLogin) {

            out.write(new byte[] {5, 0});
            out.flush();

            return true;

        }

        out.write(new byte[] {5, 2});
        out.flush();

        return authenticate(in, out);

    }

    private boolean authenticate(DataInputStream in, OutputStream out) throws IOException {

        in.read();

        String login = readField(in);
        String secret = readField(in);
        boolean accepted = mode != Mode.REJECT_LOGIN && login.equals(user) && secret.equals(password);

        authChecked.set(true);
        authSucceeded.set(accepted);
        out.write(new byte[] {1, (byte) (accepted ? 0 : 1)});
        out.flush();

        return accepted;

    }

    private static String readField(DataInputStream in) throws IOException {
        return new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
    }

    private String readConnectRequest(DataInputStream in) throws IOException {

        if (in.read() != 5 || in.read() != 1 || in.read() != 0) {
            return null;
        }

        int type = in.read();
        String host;

        if (type == 1) {
            host = InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
        } else if (type == 3) {
            host = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
        } else if (type == 4) {
            host = InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
        } else {
            return null;
        }

        return host + ":" + in.readUnsignedShort();

    }

    private byte replyCode() {
        return switch (mode) {
            case REFUSE_CONNECT -> 2;
            case REFUSE_TARGET -> 5;
            default -> 0;
        };
    }

    private static byte[] replyBytes(byte reply) {

        byte[] bytes = REPLY_OK.clone();
        bytes[1] = reply;

        return bytes;

    }

    private static Socket connectTarget(String target) throws IOException {

        Socket socket = new Socket();
        int colon = target.lastIndexOf(':');

        socket.connect(new InetSocketAddress(target.substring(0, colon),
                Integer.parseInt(target.substring(colon + 1))), 5_000);

        return socket;

    }

    private void relay(Socket client, Socket target) {

        Thread upstream = new Thread(() -> copy(target, client), "fake-socks5-relay");

        upstream.setDaemon(true);
        upstream.start();
        copy(client, target);

    }

    private void copy(Socket from, Socket to) {

        try {

            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;

            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }

            to.shutdownOutput();

        } catch (IOException ignored) {
            // one side closed the tunnel - the other copy stops on its own
        } finally {
            try {
                from.close();
                to.close();
            } catch (IOException ignored) {
                // nothing is left to report
            }
        }
    }

}

