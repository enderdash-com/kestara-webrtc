package com.enderdash.kestara.webrtc;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One inbound DataChannel message and its native delivery lease.
 *
 * <p>Consumers own each message delivered by {@link DataChannel#messages()}. Close it after use,
 * normally with try-with-resources. Closing releases native payload memory and one inbound queue
 * slot. Retaining messages intentionally applies back-pressure to the remote peer.
 */
public final class DataChannelMessage implements AutoCloseable {
    private final WebRtcRuntime runtime;
    private final AtomicLong lease;
    private final String text;
    private final ByteBuffer data;

    private DataChannelMessage(
            WebRtcRuntime runtime, long lease, String text, ByteBuffer data) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (lease == 0) {
            throw new IllegalArgumentException("Message lease must not be zero");
        }
        this.lease = new AtomicLong(lease);
        this.text = text;
        this.data = data;
    }

    static DataChannelMessage text(WebRtcRuntime runtime, long lease, String text) {
        return new DataChannelMessage(runtime, lease, Objects.requireNonNull(text, "text"), null);
    }

    static DataChannelMessage binary(WebRtcRuntime runtime, long lease, ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        if (!data.isDirect()) {
            throw new IllegalArgumentException("Inbound binary payload must be a direct buffer");
        }
        return new DataChannelMessage(runtime, lease, null, data.asReadOnlyBuffer());
    }

    /** Returns whether this is a UTF-8 text message.
     * @return {@code true} for text
     */
    public boolean isText() {
        return text != null;
    }

    /** Returns the text payload when this is a text message.
     * @return the optional text
     */
    public Optional<String> text() {
        requireOpen();
        return Optional.ofNullable(text);
    }

    /**
     * Returns a read-only direct view of the binary payload.
     *
     * @return the optional direct buffer
     */
    public Optional<ByteBuffer> data() {
        requireOpen();
        return data == null ? Optional.empty() : Optional.of(data.asReadOnlyBuffer());
    }

    /** Returns the payload size in bytes.
     * @return the encoded text or binary size
     */
    public int size() {
        requireOpen();
        return data == null ? text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : data.remaining();
    }

    /** Returns whether the delivery lease was released.
     * @return {@code true} after close
     */
    public boolean isClosed() {
        return lease.get() == 0;
    }

    @Override
    public void close() {
        long released = lease.getAndSet(0);
        if (released != 0) {
            runtime.releaseBuffer(released);
        }
    }

    private void requireOpen() {
        if (isClosed() || runtime.isClosed()) {
            throw new IllegalStateException("DataChannelMessage is closed");
        }
    }
}
