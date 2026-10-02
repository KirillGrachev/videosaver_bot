package eu.neydev.saver.core.source;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holder of the source catalog with atomic hot reload - the same pattern as
 * {@link eu.neydev.saver.core.i18n.MessageBundleHolder}: {@code /reload} rebuilds the
 * catalog from the built-in resource plus the external file, then swaps the reference.
 * In-flight requests keep reading the old immutable snapshot; the next request sees the
 * new one. No locks on the read path, no torn states.
 *
 * <p>All consumers (matcher, replies, handlers, job manager) hold the HOLDER, never a
 * catalog instance, so a reload actually reaches every layer.
 */
public final class SourceCatalogHolder {

    private final Set<String> disabled;
    private final @Nullable Path externalFile;
    private final AtomicReference<SourceCatalog> catalog = new AtomicReference<>();

    public SourceCatalogHolder(Set<String> disabled, @Nullable Path externalFile) {
        this.disabled = Set.copyOf(disabled);
        this.externalFile = externalFile;
        reload();
    }

    private SourceCatalogHolder(SourceCatalog initial) {
        this.disabled = Set.of();
        this.externalFile = null;
        this.catalog.set(initial);
    }

    /** Wraps a prebuilt catalog - for tests and embeddings that manage loading themselves. */
    public static SourceCatalogHolder of(SourceCatalog initial) {
        return new SourceCatalogHolder(initial);
    }

    public SourceCatalog catalog() {
        return catalog.get();
    }

    /** Rebuilds from the sources; a broken external file keeps the old catalog alive. */
    public synchronized void reload() {

        try {
            catalog.set(SourceCatalog.load(disabled, externalFile));
        } catch (RuntimeException e) {

            if (catalog.get() == null) {
                throw e;   // first load must fail loudly
            }

            org.slf4j.LoggerFactory.getLogger(SourceCatalogHolder.class)
                    .error("Catalog reload failed, keeping the previous catalog", e);

        }

    }

}
