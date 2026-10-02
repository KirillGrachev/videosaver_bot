package eu.neydev.saver.core.source;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One supported content source: what it is called, which hosts route to it and how it
 * is extracted. Catalog entries come from {@code sources.yml} (built-in resource merged
 * with an optional external file) - adding a source is a YAML edit, not a code change.
 *
 * @param id      stable lowercase slug used in metrics, storage and admin commands;
 * @param title   human name shown in /sources;
 * @param hosts   host patterns matched by suffix (a pattern also covers subdomains);
 * @param backend extraction technology tried first;
 * @param status  honest support level for the user-facing list;
 * @param note    optional one-line caveat ("login required for full albums").
 */
public record Source(@NotNull String id,
                     @NotNull String title,
                     @NotNull List<String> hosts,
                     @NotNull SourceBackend backend,
                     @NotNull SourceStatus status,
                     @Nullable String note) {

    public Source {

        if (id.isBlank() || !id.equals(id.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Source id must be lowercase and non-blank: " + id);
        }

        hosts = hosts.stream()
                .map(host -> host.toLowerCase(java.util.Locale.ROOT).trim())
                .filter(host -> !host.isBlank())
                .toList();

    }

    public Source(String id, String title, List<String> hosts,
                  SourceBackend backend, SourceStatus status) {
        this(id, title, hosts, backend, status, null);
    }

    public boolean isGeneric() {
        return SourceCatalog.GENERIC_ID.equals(id);
    }

}
