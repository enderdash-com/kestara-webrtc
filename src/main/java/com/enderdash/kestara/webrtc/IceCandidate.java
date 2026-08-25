package com.enderdash.kestara.webrtc;

import java.util.Objects;

/**
 * Contains one trickled ICE candidate.
 *
 * @param candidate the candidate SDP attribute without an {@code a=} prefix
 * @param sdpMid the media section identifier, or {@code null}
 * @param sdpMLineIndex the zero-based media section index, or {@code null}
 */
public record IceCandidate(String candidate, String sdpMid, Integer sdpMLineIndex) {
    /**
     * Validates this ICE candidate.
     *
     * @param candidate the candidate SDP attribute
     * @param sdpMid the media section identifier, or {@code null}
     * @param sdpMLineIndex the media section index, or {@code null}
     */
    public IceCandidate {
        Objects.requireNonNull(candidate, "candidate");
        if (sdpMLineIndex != null && sdpMLineIndex < 0) {
            throw new IllegalArgumentException("sdpMLineIndex must not be negative");
        }
    }
}
