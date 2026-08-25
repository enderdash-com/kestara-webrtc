package com.enderdash.kestara.webrtc;

import java.util.Objects;

/**
 * Immutable options for a DataChannel.
 *
 * @param ordered whether messages must arrive in order
 * @param maxPacketLifeTime maximum retransmission time in milliseconds, or {@code null}
 * @param maxRetransmits maximum retransmission count, or {@code null}
 * @param protocol application protocol name
 * @param negotiatedId pre-negotiated channel ID, or {@code null} for in-band negotiation
 */
public record DataChannelOptions(
        boolean ordered,
        Integer maxPacketLifeTime,
        Integer maxRetransmits,
        String protocol,
        Integer negotiatedId) {
    /** Default ordered and reliable DataChannel options. */
    public static final DataChannelOptions DEFAULT =
            new DataChannelOptions(true, null, null, "", null);

    /**
     * Validates these DataChannel options.
     *
     * @param ordered whether messages must arrive in order
     * @param maxPacketLifeTime maximum retransmission time in milliseconds, or {@code null}
     * @param maxRetransmits maximum retransmission count, or {@code null}
     * @param protocol application protocol name
     * @param negotiatedId pre-negotiated channel ID, or {@code null}
     */
    public DataChannelOptions {
        protocol = Objects.requireNonNull(protocol, "protocol");
        validateUnsigned16(maxPacketLifeTime, "maxPacketLifeTime");
        validateUnsigned16(maxRetransmits, "maxRetransmits");
        validateUnsigned16(negotiatedId, "negotiatedId");
        if (maxPacketLifeTime != null && maxRetransmits != null) {
            throw new IllegalArgumentException(
                    "maxPacketLifeTime and maxRetransmits cannot both be set");
        }
    }

    /**
     * Returns a copy with the ordered-delivery setting.
     *
     * @param value whether messages must arrive in order
     * @return the updated options
     */
    public DataChannelOptions withOrdered(boolean value) {
        return new DataChannelOptions(value, maxPacketLifeTime, maxRetransmits, protocol, negotiatedId);
    }

    /**
     * Returns a copy with a packet lifetime and no retransmission-count limit.
     *
     * @param value maximum lifetime in milliseconds, or {@code null}
     * @return the updated options
     */
    public DataChannelOptions withMaxPacketLifeTime(Integer value) {
        return new DataChannelOptions(ordered, value, null, protocol, negotiatedId);
    }

    /**
     * Returns a copy with a retransmission limit and no packet-lifetime limit.
     *
     * @param value maximum retransmission count, or {@code null}
     * @return the updated options
     */
    public DataChannelOptions withMaxRetransmits(Integer value) {
        return new DataChannelOptions(ordered, null, value, protocol, negotiatedId);
    }

    /**
     * Returns a copy with an application protocol name.
     *
     * @param value the protocol name
     * @return the updated options
     */
    public DataChannelOptions withProtocol(String value) {
        return new DataChannelOptions(
                ordered, maxPacketLifeTime, maxRetransmits, value, negotiatedId);
    }

    /**
     * Returns a copy with a pre-negotiated channel ID.
     *
     * @param value the channel ID, or {@code null} for in-band negotiation
     * @return the updated options
     */
    public DataChannelOptions withNegotiatedId(Integer value) {
        return new DataChannelOptions(
                ordered, maxPacketLifeTime, maxRetransmits, protocol, value);
    }

    private static void validateUnsigned16(Integer value, String name) {
        if (value != null && (value < 0 || value > 65_535)) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535");
        }
    }
}
