package com.enderdash.alloy.webrtc;

/** Selects the ICE candidate types that a peer connection can use. */
public enum IceTransportPolicy {
    /** Use direct and relayed candidates. */
    ALL,
    /** Use only candidates from TURN servers. */
    RELAY
}
