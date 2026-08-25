package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBindings;
import com.enderdash.kestara.webrtc.internal.NativeEvent;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** A bidirectional WebRTC DataChannel. */
public final class DataChannel implements AutoCloseable {
    private static final long MAX_UNSIGNED_INT = 0xffff_ffffL;
    private static final Runnable NOOP = () -> {};
    private static final Consumer<String> NOOP_ERROR = ignored -> {};

    private final WebRtcRuntime runtime;
    private final long handle;
    private final int id;
    private final String label;
    private final String protocol;
    private final boolean ordered;
    private final boolean negotiated;
    private final Integer maxPacketLifeTime;
    private final Integer maxRetransmits;
    private final long operationTimeoutMillis;
    private final Executor callbackExecutor;
    private final Runnable onTerminal;
    private final DataChannelPublisher messages;
    private final Object closeLock = new Object();
    private CompletableFuture<Void> closeFuture;
    private boolean closed;
    private boolean openNotified;
    private volatile DataChannelState state;
    private volatile Runnable onOpen = NOOP;
    private volatile Runnable onClosing = NOOP;
    private volatile Runnable onClosed = NOOP;
    private volatile Runnable onBufferedAmountLow = NOOP;
    private volatile Runnable onBufferedAmountHigh = NOOP;
    private volatile Consumer<String> onError = NOOP_ERROR;
    private volatile long bufferedAmountLowThreshold;
    private volatile long bufferedAmountHighThreshold = MAX_UNSIGNED_INT;

    DataChannel(
            WebRtcRuntime runtime,
            long handle,
            int id,
            String label,
            String protocol,
            boolean ordered,
            boolean negotiated,
            Integer maxPacketLifeTime,
            Integer maxRetransmits,
            int receiveQueueCapacity,
            long operationTimeoutMillis,
            Executor callbackExecutor,
            boolean initiallyOpen,
            Runnable onTerminal) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.handle = handle;
        this.id = id;
        this.label = Objects.requireNonNull(label, "label");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.ordered = ordered;
        this.negotiated = negotiated;
        this.maxPacketLifeTime = maxPacketLifeTime;
        this.maxRetransmits = maxRetransmits;
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.onTerminal = Objects.requireNonNull(onTerminal, "onTerminal");
        messages = new DataChannelPublisher(callbackExecutor, receiveQueueCapacity, this::closeAsync);
        state = initiallyOpen ? DataChannelState.OPEN : DataChannelState.CONNECTING;
    }

    /**
     * Returns the channel label.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /** Returns the SCTP stream identifier.
     * @return the channel identifier
     */
    public int id() {
        return id;
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

    /** Returns whether the channel was negotiated out of band.
     * @return {@code true} for an explicitly negotiated channel
     */
    public boolean negotiated() {
        return negotiated;
    }

    /** Returns the maximum packet lifetime.
     * @return milliseconds, or {@code null} for no lifetime limit
     */
    public Integer maxPacketLifeTime() {
        return maxPacketLifeTime;
    }

    /** Returns the maximum retransmission count.
     * @return the count, or {@code null} for no retransmission limit
     */
    public Integer maxRetransmits() {
        return maxRetransmits;
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
     * Sets the callback fired when queued bytes cross the low threshold.
     *
     * @param callback the callback
     */
    public void onBufferedAmountLow(Runnable callback) {
        onBufferedAmountLow = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the callback fired when queued bytes cross the high threshold.
     *
     * @param callback the callback
     */
    public void onBufferedAmountHigh(Runnable callback) {
        onBufferedAmountHigh = Objects.requireNonNull(callback, "callback");
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
     * Returns the single-subscriber, backpressured inbound message stream.
     *
     * <p>The subscriber must close every delivered message. An unclosed message retains one
     * receive slot and, once the configured capacity is exhausted, pauses native delivery.
     *
     * @return the message publisher
     */
    public Flow.Publisher<DataChannelMessage> messages() {
        return messages;
    }

    /**
     * Sends a UTF-8 text message without blocking the caller.
     *
     * @param text the message
     * @return the send stage
     */
    public CompletionStage<Void> sendAsync(String text) {
        Objects.requireNonNull(text, "text");
        requireOpen();
        return runtime.sendTextAsync(handle, text, operationTimeoutMillis);
    }

    /**
     * Sends a UTF-8 text message and waits for native acceptance.
     *
     * @param text the message
     */
    public void send(String text) {
        runtime.await(sendAsync(text), operationTimeoutMillis);
    }

    /**
     * Sends a binary message without blocking the caller.
     *
     * @param data the data, which is copied before this method returns
     * @return the send stage
     */
    public CompletionStage<Void> sendAsync(byte[] data) {
        Objects.requireNonNull(data, "data");
        requireOpen();
        return runtime.sendBinaryAsync(handle, data.clone(), operationTimeoutMillis);
    }

    /**
     * Sends a binary message and waits for native acceptance.
     *
     * @param data the data, which is copied before this method returns
     */
    public void send(byte[] data) {
        runtime.await(sendAsync(data), operationTimeoutMillis);
    }

    /**
     * Sends the remaining bytes in a buffer without changing its position or blocking the caller.
     *
     * @param data the source data, which is copied before this method returns
     * @return the send stage
     */
    public CompletionStage<Void> sendAsync(ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        requireOpen();
        byte[] copy = new byte[data.remaining()];
        data.duplicate().get(copy);
        return runtime.sendBinaryAsync(handle, copy, operationTimeoutMillis);
    }

    /**
     * Sends the remaining bytes in a buffer and waits for native acceptance.
     *
     * @param data the source data, which is copied before this method returns
     */
    public void send(ByteBuffer data) {
        runtime.await(sendAsync(data), operationTimeoutMillis);
    }

    /**
     * Transfers a native-owned direct buffer to the send pipeline without copying through JNI.
     *
     * @param data the buffer to consume. The method sends its current remaining range.
     * @return the send stage
     */
    public CompletionStage<Void> sendAsync(NativeBuffer data) {
        Objects.requireNonNull(data, "data");
        requireOpen();
        return runtime.sendBufferAsync(handle, data, operationTimeoutMillis);
    }

    /** Sends and consumes a native-owned direct buffer.
     * @param data the buffer to consume
     */
    public void send(NativeBuffer data) {
        runtime.await(sendAsync(data), operationTimeoutMillis);
    }

    /**
     * Attempts to queue text without waiting for send-buffer capacity.
     *
     * @param text the text
     * @return {@code false} when the send buffer is full
     */
    public CompletionStage<Boolean> trySendAsync(String text) {
        Objects.requireNonNull(text, "text");
        requireOpen();
        return runtime.trySendTextAsync(handle, text, operationTimeoutMillis);
    }

    /**
     * Attempts to queue binary data without waiting for send-buffer capacity.
     *
     * @param data the data
     * @return {@code false} when the send buffer is full
     */
    public CompletionStage<Boolean> trySendAsync(byte[] data) {
        Objects.requireNonNull(data, "data");
        requireOpen();
        return runtime.trySendBinaryAsync(handle, data.clone(), operationTimeoutMillis);
    }

    /**
     * Attempts to queue the remaining buffer bytes without waiting for capacity.
     *
     * @param data the source buffer
     * @return {@code false} when the send buffer is full
     */
    public CompletionStage<Boolean> trySendAsync(ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        byte[] copy = new byte[data.remaining()];
        data.duplicate().get(copy);
        return trySendAsync(copy);
    }

    /** Attempts to transfer a native-owned direct buffer without waiting for capacity.
     * @param data the buffer to consume
     * @return {@code false} when the send buffer is full
     */
    public CompletionStage<Boolean> trySendAsync(NativeBuffer data) {
        Objects.requireNonNull(data, "data");
        requireOpen();
        return runtime.trySendBufferAsync(handle, data, operationTimeoutMillis);
    }

    /**
     * Attempts to queue text without waiting for send-buffer capacity.
     *
     * @param text the text
     * @return {@code false} when the send buffer is full
     */
    public boolean trySend(String text) {
        return runtime.await(trySendAsync(text), operationTimeoutMillis);
    }

    /**
     * Attempts to queue binary data without waiting for send-buffer capacity.
     *
     * @param data the data
     * @return {@code false} when the send buffer is full
     */
    public boolean trySend(byte[] data) {
        return runtime.await(trySendAsync(data), operationTimeoutMillis);
    }

    /**
     * Attempts to queue the remaining buffer bytes without waiting for capacity.
     *
     * @param data the source buffer
     * @return {@code false} when the send buffer is full
     */
    public boolean trySend(ByteBuffer data) {
        return runtime.await(trySendAsync(data), operationTimeoutMillis);
    }

    /** Attempts to transfer a native-owned direct buffer.
     * @param data the buffer to consume
     * @return {@code false} when the send buffer is full
     */
    public boolean trySend(NativeBuffer data) {
        return runtime.await(trySendAsync(data), operationTimeoutMillis);
    }

    /**
     * Completes when the channel has send-buffer capacity.
     *
     * @return the writable stage
     */
    public CompletionStage<Void> writableAsync() {
        requireOpen();
        return runtime.dataChannelWritableAsync(handle, operationTimeoutMillis);
    }

    /** Waits until the channel has send-buffer capacity. */
    public void writable() {
        runtime.await(writableAsync(), operationTimeoutMillis);
    }

    /**
     * Returns the number of bytes queued for SCTP acknowledgement.
     *
     * @return the outstanding-byte stage
     */
    public CompletionStage<Long> outstandingBytesAsync() {
        return runtime.dataChannelOutstandingBytesAsync(handle, operationTimeoutMillis);
    }

    /**
     * Returns the number of bytes queued for SCTP acknowledgement.
     *
     * @return the outstanding byte count
     */
    public long outstandingBytes() {
        return runtime.await(outstandingBytesAsync(), operationTimeoutMillis);
    }

    /**
     * Returns application bytes still buffered by the native send pipeline.
     *
     * @return the buffered byte count
     */
    public long bufferedAmount() {
        return outstandingBytes();
    }

    /** Returns buffered application bytes without blocking the caller.
     * @return the buffered byte-count stage
     */
    public CompletionStage<Long> bufferedAmountAsync() {
        return outstandingBytesAsync();
    }

    /** Returns the low buffered-amount event threshold.
     * @return the threshold in bytes
     */
    public long bufferedAmountLowThreshold() {
        return bufferedAmountLowThreshold;
    }

    /** Returns the high buffered-amount event threshold.
     * @return the threshold in bytes
     */
    public long bufferedAmountHighThreshold() {
        return bufferedAmountHighThreshold;
    }

    /**
     * Sets the low and high buffered-amount event thresholds.
     *
     * @param low the low threshold in bytes
     * @param high the high threshold in bytes
     * @return the update stage
     */
    public CompletionStage<Void> setBufferedAmountThresholdsAsync(long low, long high) {
        requireOpen();
        if (low < 0 || high < 0 || low > high || high > MAX_UNSIGNED_INT) {
            throw new IllegalArgumentException(
                    "Buffered amount thresholds must satisfy 0 <= low <= high <= 4294967295");
        }
        return runtime.setDataChannelThresholdsAsync(handle, low, high, operationTimeoutMillis)
                .thenRun(() -> {
                    bufferedAmountLowThreshold = low;
                    bufferedAmountHighThreshold = high;
                });
    }

    /**
     * Sets the low and high buffered-amount event thresholds.
     *
     * @param low the low threshold in bytes
     * @param high the high threshold in bytes
     */
    public void setBufferedAmountThresholds(long low, long high) {
        runtime.await(setBufferedAmountThresholdsAsync(low, high), operationTimeoutMillis);
    }

    /**
     * Starts closing the channel without blocking the caller.
     *
     * @return the shared close stage
     */
    public CompletionStage<Void> closeAsync() {
        synchronized (closeLock) {
            if (closeFuture != null) {
                return closeFuture;
            }
            state = DataChannelState.CLOSING;
            closeFuture = new CompletableFuture<>();
            runtime.closeDataChannelAsync(handle, operationTimeoutMillis)
                    .whenComplete((ignored, error) -> {
                        markClosed();
                        if (error == null) {
                            closeFuture.complete(null);
                        } else {
                            closeFuture.completeExceptionally(error);
                        }
                    });
            return closeFuture;
        }
    }

    /** Starts closing the channel and waits for native completion. */
    @Override
    public void close() {
        runtime.await(closeAsync(), operationTimeoutMillis);
    }

    void handleNativeEvent(NativeEvent event) {
        switch (event.kind()) {
            case NativeBindings.EVENT_DATA_CHANNEL_OPEN -> callbackExecutor.execute(this::notifyOpen);
            case NativeBindings.EVENT_DATA_CHANNEL_CLOSING -> {
                state = DataChannelState.CLOSING;
                callbackExecutor.execute(onClosing);
            }
            case NativeBindings.EVENT_DATA_CHANNEL_CLOSED -> {
                markClosed();
                callbackExecutor.execute(onClosed);
            }
            case NativeBindings.EVENT_DATA_CHANNEL_ERROR ->
                    callbackExecutor.execute(() -> onError.accept(
                            event.text() == null ? "DataChannel error" : event.text()));
            case NativeBindings.EVENT_DATA_CHANNEL_TEXT -> messages.publish(DataChannelMessage.text(
                    runtime,
                    event.messageHandle(),
                    event.text() == null ? "" : event.text()));
            case NativeBindings.EVENT_DATA_CHANNEL_BINARY -> {
                ByteBuffer payload = Objects.requireNonNull(event.directData(), "native binary payload");
                messages.publish(DataChannelMessage.binary(runtime, event.messageHandle(), payload));
            }
            case NativeBindings.EVENT_DATA_CHANNEL_BUFFERED_AMOUNT_LOW ->
                    callbackExecutor.execute(onBufferedAmountLow);
            case NativeBindings.EVENT_DATA_CHANNEL_BUFFERED_AMOUNT_HIGH ->
                    callbackExecutor.execute(onBufferedAmountHigh);
            default -> throw new IllegalArgumentException(
                    "Unsupported DataChannel event: " + event.kind());
        }
    }

    void markClosed() {
        markClosed(false);
    }

    void closeForRuntimeShutdown() {
        markClosed(true);
    }

    private void markClosed(boolean discardMessages) {
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closed = true;
            state = DataChannelState.CLOSED;
            onTerminal.run();
            if (discardMessages) {
                messages.discard();
            } else {
                messages.complete();
            }
            if (closeFuture == null) {
                closeFuture = CompletableFuture.completedFuture(null);
            }
        }
    }

    void notifyOpen() {
        synchronized (closeLock) {
            if (openNotified || closed) {
                return;
            }
            openNotified = true;
            state = DataChannelState.OPEN;
        }
        onOpen.run();
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("DataChannel is not open");
        }
    }
}
