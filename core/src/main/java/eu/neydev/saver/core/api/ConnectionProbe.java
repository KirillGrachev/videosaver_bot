package eu.neydev.saver.core.api;

/**
 * The adapter's optional contract with background connection: {@code true} when
 * the transport is already installed. Bootstrap uses it to log honestly
 * "active" versus "connecting in the background" when starting with an unreachable network.
 */
public interface ConnectionProbe {

    boolean connected();

}

