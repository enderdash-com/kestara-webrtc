package com.enderdash.kestara.webrtc;

/** State of the SDP offer and answer exchange. */
public enum SignalingState {
    /** Native state was not specified. */
    UNSPECIFIED,
    /** No offer or answer exchange is active. */
    STABLE,
    /** A local offer is waiting for a remote answer. */
    HAVE_LOCAL_OFFER,
    /** A remote offer is waiting for a local answer. */
    HAVE_REMOTE_OFFER,
    /** A local provisional answer was applied. */
    HAVE_LOCAL_PRANSWER,
    /** A remote provisional answer was applied. */
    HAVE_REMOTE_PRANSWER,
    /** The connection is closed. */
    CLOSED
}
