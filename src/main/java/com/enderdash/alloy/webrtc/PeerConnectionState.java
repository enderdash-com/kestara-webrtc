package com.enderdash.alloy.webrtc;

/** Describes the aggregate state of a peer connection. */
public enum PeerConnectionState {
    /** The peer has not started connecting. */
    NEW,
    /** The peer is establishing transport connectivity. */
    CONNECTING,
    /** The peer is connected. */
    CONNECTED,
    /** The peer temporarily lost connectivity. */
    DISCONNECTED,
    /** The peer connection failed. */
    FAILED,
    /** The peer connection is closed. */
    CLOSED
}
