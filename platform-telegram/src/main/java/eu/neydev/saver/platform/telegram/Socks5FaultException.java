package eu.neydev.saver.platform.telegram;

import java.net.SocketException;

/**
 * A SOCKS5 fault that a retry cannot fix: a rejected login, a proxy that demands credentials
 * the configuration does not carry, or a server that does not speak SOCKS5 at all.
 *
 * <p>It extends {@link SocketException} rather than {@link java.net.ConnectException} because
 * OkHttp rewraps only ConnectException into "Failed to connect to ..." and would bury the reason.
 * The adapter treats it as a fatal startup error: looping with a wrong password is pointless
 * and looks exactly like a dead proxy.
 */
final class Socks5FaultException extends SocketException {

    /** A stable prefix of every message: some wrappers keep only the text and drop the cause. */
    static final String MARKER = "SOCKS5 proxy";

    Socks5FaultException(String message) {
        super(message);
    }

}

