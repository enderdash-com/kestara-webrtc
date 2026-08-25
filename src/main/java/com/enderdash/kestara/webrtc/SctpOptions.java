package com.enderdash.kestara.webrtc;

/** Immutable SCTP transport options. */
public record SctpOptions(int sendBufferLimit, int receiveBufferSize, int maximumMessageSize) {
    /** Default SCTP options. */
    public static final SctpOptions DEFAULT = new SctpOptions(16 * 1024 * 1024, 1024 * 1024, 65_536);

    /**
     * Creates SCTP options.
     *
     * @param sendBufferLimit the per-channel send-buffer limit, or {@code 0} for no limit
     * @param receiveBufferSize the receive window in bytes
     * @param maximumMessageSize the maximum message size in bytes
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
    }

    /**
     * Returns a copy with the per-channel native send-buffer limit.
     *
     * @param bytes the limit, or {@code 0} for no limit
     * @return the updated options
     */
    public SctpOptions withSendBufferLimit(int bytes) {
        return new SctpOptions(bytes, receiveBufferSize, maximumMessageSize);
    }

    /**
     * Returns a copy with the SCTP receive window.
     *
     * @param bytes the receive window in bytes
     * @return the updated options
     */
    public SctpOptions withReceiveBufferSize(int bytes) {
        return new SctpOptions(sendBufferLimit, bytes, maximumMessageSize);
    }

    /**
     * Returns a copy with the maximum inbound or outbound message size.
     *
     * @param bytes the maximum message size in bytes
     * @return the updated options
     */
    public SctpOptions withMaximumMessageSize(int bytes) {
        return new SctpOptions(sendBufferLimit, receiveBufferSize, bytes);
    }
}
