package com.enderdash.alloy.webrtc;

import com.enderdash.alloy.webrtc.internal.NativeBindings;
import com.enderdash.alloy.webrtc.internal.NativeEvent;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A bidirectional WebRTC DataChannel. */
public final class DataChannel implements AutoCloseable {
    private static final Runnable NOOP = () -> {};
    private static final Consumer<String> NOOP_ERROR = ignored -> {};
    private static final DataChannelMessageHandler NOOP_MESSAGE = new DataChannelMessageHandler() {};

    private final long handle;
    private final String label;
    private final String protocol;
    private final boolean ordered;
    private final Executor callbackExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean openNotified = new AtomicBoolean();
    private volatile DataChannelState state;
    private volatile Runnable onOpen = NOOP;
    private volatile Runnable onClosing = NOOP;
    private volatile Runnable onClosed = NOOP;
    private volatile Consumer<String> onError = NOOP_ERROR;
    private volatile DataChannelMessageHandler onMessage = NOOP_MESSAGE;

    DataChannel(
            long handle,
            String label,
            String protocol,
            boolean ordered,
            Executor callbackExecutor,
            boolean initiallyOpen) {
        this.handle = handle;
        this.label = Objects.requireNonNull(label, "label");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.ordered = ordered;
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.state = initiallyOpen ? DataChannelState.OPEN : DataChannelState.CONNECTING;
    }

    /**
     * Returns the channel label.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * Returns the application protocol name.
     *
     * @return the protocol name
     */
    public String protocol() {
        return protocol;
    }

    /**
     * Returns whether messages use ordered delivery.
     *
     * @return {@code true} for ordered delivery
     */
    public boolean ordered() {
        return ordered;
    }

    /**
     * Returns the current channel state.
     *
     * @return the state
     */
    public DataChannelState state() {
        return state;
    }

    /**
     * Returns whether the channel can send messages.
     *
     * @return {@code true} when the channel is open
     */
    public boolean isOpen() {
        return state == DataChannelState.OPEN;
    }

    /**
     * Sets the channel-open callback.
     *
     * @param callback the callback
     */
    public void onOpen(Runnable callback) {
        onOpen = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the channel-closing callback.
     *
     * @param callback the callback
     */
    public void onClosing(Runnable callback) {
        onClosing = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the channel-closed callback.
     *
     * @param callback the callback
     */
    public void onClosed(Runnable callback) {
        onClosed = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the channel-error callback.
     *
     * @param callback the callback
     */
    public void onError(Consumer<String> callback) {
        onError = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the message callback.
     *
     * @param callback the callback
     */
    public void onMessage(DataChannelMessageHandler callback) {
        onMessage = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sends a UTF-8 text message.
     *
     * @param text the text
     */
    public void send(String text) {
        Objects.requireNonNull(text, "text");
        requireOpen();
        NativeBindings.sendDataChannelText(handle, text);
    }

    /**
     * Sends a binary message.
     *
     * @param data the data; Alloy copies it before this method returns
     */
    public void send(byte[] data) {
        Objects.requireNonNull(data, "data");
        requireOpen();
        NativeBindings.sendDataChannelBinary(handle, data);
    }

    /**
     * Sends the remaining bytes in a buffer without changing its position.
     *
     * @param data the data; Alloy copies it before this method returns
     */
    public void send(ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        byte[] copy = new byte[data.remaining()];
        data.duplicate().get(copy);
        send(copy);
    }

    /** Starts closing the channel. This method has no effect after the first call. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = DataChannelState.CLOSING;
        NativeBindings.closeDataChannel(handle);
    }

    void handleNativeEvent(NativeEvent event) {
        switch (event.kind()) {
            case NativeBindings.EVENT_DATA_CHANNEL_OPEN -> {
                callbackExecutor.execute(this::notifyOpen);
            }
            case NativeBindings.EVENT_DATA_CHANNEL_CLOSING -> {
                state = DataChannelState.CLOSING;
                callbackExecutor.execute(onClosing);
            }
            case NativeBindings.EVENT_DATA_CHANNEL_CLOSED -> {
                closed.set(true);
                state = DataChannelState.CLOSED;
                callbackExecutor.execute(onClosed);
            }
            case NativeBindings.EVENT_DATA_CHANNEL_ERROR ->
                    callbackExecutor.execute(() -> onError.accept(
                            event.text() == null ? "DataChannel error" : event.text()));
            case NativeBindings.EVENT_DATA_CHANNEL_TEXT -> callbackExecutor.execute(() ->
                    onMessage.onText(event.text() == null ? "" : event.text()));
            case NativeBindings.EVENT_DATA_CHANNEL_BINARY -> {
                byte[] payload = event.data() == null ? new byte[0] : event.data();
                ByteBuffer buffer = ByteBuffer.wrap(payload).asReadOnlyBuffer();
                callbackExecutor.execute(() -> onMessage.onBinary(buffer));
            }
            default -> throw new IllegalArgumentException("Unsupported DataChannel event: " + event.kind());
        }
    }

    void markClosed() {
        closed.set(true);
        state = DataChannelState.CLOSED;
    }

    void notifyOpen() {
        if (openNotified.compareAndSet(false, true)) {
            state = DataChannelState.OPEN;
            onOpen.run();
        }
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("DataChannel is not open");
        }
    }
}
