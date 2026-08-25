package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBufferDescriptor;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A direct buffer whose storage is owned by a {@link WebRtcRuntime}.
 *
 * <p>Close an unsent buffer to release its native storage. Sending this buffer transfers
 * ownership to the native transport. Do not use a previously obtained {@link #buffer()} view
 * after either operation.
 */
public final class NativeBuffer implements AutoCloseable {
    private final WebRtcRuntime runtime;
    private final AtomicLong handle;
    private final ByteBuffer buffer;

    NativeBuffer(WebRtcRuntime runtime, NativeBufferDescriptor descriptor) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(descriptor, "descriptor");
        handle = new AtomicLong(descriptor.handle());
        buffer = Objects.requireNonNull(descriptor.buffer(), "native buffer");
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Native buffer view must be direct");
        }
    }

    /**
     * Returns the mutable direct view used to prepare a message.
     *
     * @return the direct buffer
     */
    public ByteBuffer buffer() {
        requireOwned();
        return buffer;
    }

    /** Returns whether this object still owns its native allocation.
     * @return {@code true} before transfer or close
     */
    public boolean isOwned() {
        return handle.get() != 0 && !runtime.isClosed();
    }

    Transfer transfer(WebRtcRuntime expectedRuntime) {
        if (runtime != expectedRuntime) {
            throw new IllegalArgumentException("NativeBuffer belongs to a different WebRtcRuntime");
        }
        int offset = buffer.position();
        int length = buffer.remaining();
        long transferred = handle.getAndSet(0);
        if (transferred == 0) {
            throw new IllegalStateException("NativeBuffer was already transferred or closed");
        }
        return new Transfer(transferred, offset, length);
    }

    @Override
    public void close() {
        long released = handle.getAndSet(0);
        if (released != 0) {
            runtime.releaseBuffer(released);
        }
    }

    private void requireOwned() {
        if (!isOwned()) {
            throw new IllegalStateException("NativeBuffer was transferred or closed");
        }
    }

    record Transfer(long handle, int offset, int length) {}
}
