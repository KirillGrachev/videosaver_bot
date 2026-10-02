package eu.neydev.saver.core.config;

/** A configuration error with a human-readable path to the problem key. */
public class ConfigException extends RuntimeException {

    public ConfigException(String path, String message) {
        super("Configuration, key '%s': %s".formatted(path, message));
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }

}

