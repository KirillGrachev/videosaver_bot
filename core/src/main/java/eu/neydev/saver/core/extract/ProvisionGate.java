package eu.neydev.saver.core.extract;

import java.time.Duration;

/**
 * The cold-start seam between the extraction chain and whoever installs the tools
 * ({@code ToolProvisioner} in production): the first jobs after a restart arrive
 * WHILE the binaries are still downloading, and failing them with TOOL_MISSING at
 * that moment is a self-inflicted outage, not an honest answer.
 *
 * <p>The chain consults the gate only when the source's OWN backend is unavailable:
 * a backend that needs no tools (http, direct) must not pay for somebody else's
 * download, and a run that already ended (failed checksum, unreachable release
 * endpoint) answers without delay - the bounded wait covers an IN-FLIGHT run only.
 */
public interface ProvisionGate {

    /** True while an install run is in flight: started, not yet finished. */
    boolean runActive();

    /**
     * Blocks until the in-flight run ends or {@code timeout} passes. Returns true
     * immediately when no run is in flight; true when the run ended within the
     * window (however it ended - a finished run re-probes the toolchain and the
     * caller re-checks availability); false when the run is STILL active after the
     * window, i.e. the caller should give up and answer honestly.
     */
    boolean awaitRun(Duration timeout);

}
