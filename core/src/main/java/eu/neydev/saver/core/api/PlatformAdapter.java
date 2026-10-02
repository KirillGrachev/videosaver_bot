package eu.neydev.saver.core.api;

/**
 * The platform adapter contract. The implementation lives in its own module
 * (platform-telegram / platform-vk / platform-discord) and is a THIN layer:
 * mapping updates into {@link IncomingUpdate}, transliterating {@link OutboundMessage}
 * into API calls and honest {@link PlatformException} errors.
 *
 * <p>Lifecycle: {@link #start(PlatformContext)} starts the receiving threads,
 * {@link #stop()} - stops them cleanly (graceful shutdown).
 * {@link #execute(OutboundMessage)} is called by the outbound dispatcher threads.
 */
public interface PlatformAdapter {

    Platform platform();

    /** Start receiving updates; events are handed to {@code sink}. */
    void start(PlatformContext context);

    /** Stop receiving (idempotent). */
    void stop();

    /**
     * Execute an outbound command synchronously (a blocking platform API call).
     *
     * @return the platform's answer (message id when it reports one);
     * @throws PlatformException.RateLimitedException    on platform flood-control;
     * @throws PlatformException.PermanentDeliveryException if delivery is impossible forever;
     * @throws PlatformException                            on other failures (network, 5xx).
     */
    SendResult execute(OutboundMessage message);

    /** The platform is enabled in the configuration and ready to start. */
    boolean isEnabled();

    /**
     * Can the adapter really EDIT a sent message? WhatsApp and Viber cannot: their
     * adapters turn an Edit into a fresh message, which is fine for a menu re-render
     * but becomes spam when a download job streams progress. The job manager asks
     * this before sending progress edits - honesty about platform capabilities
     * belongs in the contract, not in per-caller if-chains.
     */
    default boolean supportsEdits() {
        return true;
    }

    /**
     * Native album sending (Telegram sendMediaGroup). When false, the outbound
     * dispatcher expands a {@link OutboundMessage.SendMediaGroup} into per-file
     * sends by itself - adapters never see groups they did not declare support for.
     */
    default boolean supportsMediaGroups() {
        return false;
    }

}

