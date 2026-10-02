package eu.neydev.saver.core.api;

/**
 * Supported platforms. The {@link #id()} value is used in profile keys,
 * metrics and configuration - this is the only canonical string representation.
 */
public enum Platform {

    TELEGRAM("telegram"),
    VK("vk"),
    DISCORD("discord"),
    SLACK("slack"),
    WHATSAPP("whatsapp"),
    VIBER("viber"),
    /** In-game chat of a Bukkit/Paper Minecraft server (plugin-bukkit module). */
    BUKKIT("bukkit");

    private final String id;

    Platform(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Platform fromId(String id) {

        for (Platform platform : values()) {
            if (platform.id.equalsIgnoreCase(id)) {
                return platform;
            }
        }

        throw new IllegalArgumentException("Unknown platform: " + id);

    }

}

