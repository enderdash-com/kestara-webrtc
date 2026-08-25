package com.enderdash.kestara.webrtc;

/** Describes the state of ICE connectivity. */
public enum IceConnectionState {
    /** ICE has not started. */
    NEW,
    /** ICE is checking candidate pairs. */
    CHECKING,
    /** ICE found a usable candidate pair. */
    CONNECTED,
    /** ICE finished gathering and checking. */
    COMPLETED,
    /** Connectivity checks lost contact with the peer. */
    DISCONNECTED,
    /** ICE could not find or keep a usable candidate pair. */
    FAILED,
    /** The peer connection is closed. */
    CLOSED
}
