package com.enderdash.kestara.webrtc;

import java.util.Objects;

/**
 * Contains an SDP offer or answer.
 *
 * @param sdp the session description
 * @param type the negotiation role
 */
public record SessionDescription(String sdp, SessionDescriptionType type) {
    /**
     * Validates this session description.
     *
     * @param sdp the session description
     * @param type the negotiation role
     */
    public SessionDescription {
        Objects.requireNonNull(sdp, "sdp");
        Objects.requireNonNull(type, "type");
    }
}
