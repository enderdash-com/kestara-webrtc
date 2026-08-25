package com.enderdash.kestara.webrtc;

/** Immutable SCTP and DataChannel delivery options. */
public record SctpOptions(
        int sendBufferLimit,
        int receiveBufferSize,
        int maximumMessageSize,
        int receiveQueueCapacity) {
    /** Default SCTP options. */
    public static final SctpOptions DEFAULT =
            new SctpOptions(16 * 1024 * 1024, 1024 * 1024, 65_536, 64);

    /**
     * Creates SCTP options.
     *
     * @param sendBufferLimit the per-channel send-buffer limit, or {@code 0} for no limit
     * @param receiveBufferSize the receive window in bytes
     * @param maximumMessageSize the maximum message size in bytes
     * @param receiveQueueCapacity maximum leased inbound messages per channel
     */
    public SctpOptions {
        if (sendBufferLimit < 0) {
            throw new IllegalArgumentException("SCTP send buffer limit must not be negative");
        }
        if (maximumMessageSize < 1 || maximumMessageSize > 256 * 1024) {
            throw new IllegalArgumentException("SCTP maximum message size must be between 1 and 262144");
        }
        if (receiveBufferSize < 1_500 || receiveBufferSize < maximumMessageSize) {
            throw new IllegalArgumentException(
                    "SCTP receive buffer size must be at least 1500 and not less than the maximum message size");
        }
        if (receiveQueueCapacity < 1 || receiveQueueCapacity > 65_536) {
            throw new IllegalArgumentException(
                    "DataChannel receive queue capacity must be between 1 and 65536");
        }
    }

    /**
     * Returns a copy with the per-channel native send-buffer limit.
     *
     * @param bytes the limit, or {@code 0} for no limit
     * @return the updated options
     */
    public SctpOptions withSendBufferLimit(int bytes) {
        return new SctpOptions(bytes, receiveBufferSize, maximumMessageSize, receiveQueueCapacity);
    }

    /**
     * Returns a copy with the SCTP receive window.
     *
     * @param bytes the receive window in bytes
     * @return the updated options
     */
    public SctpOptions withReceiveBufferSize(int bytes) {
        return new SctpOptions(sendBufferLimit, bytes, maximumMessageSize, receiveQueueCapacity);
    }

    /**
     * Returns a copy with the maximum inbound or outbound message size.
     *
     * @param bytes the maximum message size in bytes
     * @return the updated options
     */
    public SctpOptions withMaximumMessageSize(int bytes) {
        return new SctpOptions(sendBufferLimit, receiveBufferSize, bytes, receiveQueueCapacity);
    }

    /**
     * Returns a copy with a per-channel inbound delivery limit.
     *
     * <p>Each delivered message retains one slot until the application closes it. When every
     * slot is leased, native polling pauses and back-pressure reaches the SCTP transport.
     *
     * @param messages the maximum leased messages
     * @return the updated options
     */
    public SctpOptions withReceiveQueueCapacity(int messages) {
        return new SctpOptions(sendBufferLimit, receiveBufferSize, maximumMessageSize, messages);
    }
}
