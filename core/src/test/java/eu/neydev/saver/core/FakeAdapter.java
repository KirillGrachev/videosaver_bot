package eu.neydev.saver.core;

import eu.neydev.saver.core.api.OutboundMessage;
import eu.neydev.saver.core.api.Platform;
import eu.neydev.saver.core.api.PlatformAdapter;
import eu.neydev.saver.core.api.PlatformContext;
import eu.neydev.saver.core.api.SendResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A test adapter: accumulates outbound commands in memory, sends nothing. */
public final class FakeAdapter implements PlatformAdapter {

    private final List<OutboundMessage> sent = new CopyOnWriteArrayList<>();
    private final Platform platform;
    private RuntimeException failure;

    public FakeAdapter(Platform platform) {
        this.platform = platform;
    }

    public FakeAdapter failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public List<OutboundMessage> sent() {
        return sent;
    }

    @SuppressWarnings("unchecked")
    public <T extends OutboundMessage> List<T> sentOfType(Class<T> type) {
        return sent.stream().filter(type::isInstance).map(message -> (T) message).toList();
    }

    @Override
    public Platform platform() {
        return platform;
    }

    @Override
    public void start(PlatformContext context) {
    }

    @Override
    public void stop() {
    }

    @Override
    public SendResult execute(OutboundMessage message) {
        if (failure != null) throw failure;
        sent.add(message);
        return SendResult.of("fake-" + sent.size());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

}

