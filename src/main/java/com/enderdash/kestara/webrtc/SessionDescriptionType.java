package com.enderdash.kestara.webrtc;

/** Identifies the negotiation role of an SDP session description. */
public enum SessionDescriptionType {
    /** An SDP offer. */
    OFFER,
    /** A final SDP answer. */
    ANSWER,
    /** A provisional SDP answer. */
    PRANSWER,
    /** A request to roll back pending negotiation. */
    ROLLBACK
}
