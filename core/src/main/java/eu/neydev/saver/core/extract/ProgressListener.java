package eu.neydev.saver.core.extract;

import org.jetbrains.annotations.Nullable;

/**
 * Download progress, pushed by extractors and throttled by the job manager before it
 * becomes a message edit (platforms rate-limit edits harder than sends, and a percent
 * per stdout line would be a flood).
 */
public interface ProgressListener {

    ProgressListener NOOP = new ProgressListener() {
    };

    /**
     * @param percent   0..100 when the tool reports it, {@code null} for indeterminate
     *                  stages ("merging", "extracting audio", galleries);
     * @param stage     short human-readable stage for indeterminate phases, may be null;
     * @param itemsDone items finished so far (galleries/playlists);
     * @param itemsTotal expected item count, 0 when unknown.
     */
    default void onProgress(@Nullable Integer percent, @Nullable String stage,
                            int itemsDone, int itemsTotal) {
    }

    /** Cancelled jobs stop calling in; extractors poll their own interrupt anyway. */
    default boolean cancelled() {
        return false;
    }

}
