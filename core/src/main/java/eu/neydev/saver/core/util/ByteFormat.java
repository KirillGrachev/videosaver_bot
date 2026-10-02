package eu.neydev.saver.core.util;

import java.util.Locale;

/**
 * Human-readable byte sizes for captions, quotas and logs ("4.2 MB"). Decimal units
 * on purpose: platforms quote their limits in decimal megabytes (Telegram "50 MB"
 * = 50_000_000 bytes), and mixing binary MiB into user-facing text invites the
 * "but the file is only 48 MB!" support thread.
 */
public final class ByteFormat {

    private ByteFormat() {
    }

    public static String human(long bytes) {

        if (bytes < 1_000) {
            return bytes + " B";
        }

        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;

        while (value >= 1_000 && unit < units.length - 1) {
            value /= 1_000;
            unit++;
        }

        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s",
                value, units[unit]);

    }

    /** Parses config values like {@code 500mb}, {@code 2GB}, {@code 512kb}, {@code 1048576}. */
    public static long parse(String text, String key) {

        String value = text.trim().toLowerCase(Locale.ROOT);

        long multiplier = 1;

        if (value.endsWith("tb")) {
            multiplier = 1_000_000_000_000L;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("gb")) {
            multiplier = 1_000_000_000L;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("mb")) {
            multiplier = 1_000_000L;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("kb")) {
            multiplier = 1_000L;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("b")) {
            value = value.substring(0, value.length() - 1);
        }

        try {
            return (long) (Double.parseDouble(value.trim()) * multiplier);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse size for '" + key + "': " + text);
        }

    }

}
