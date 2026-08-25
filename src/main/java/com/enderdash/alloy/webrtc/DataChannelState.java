package com.enderdash.alloy.webrtc;

/** Describes the state of a DataChannel. */
public enum DataChannelState {
    /** The channel is being established. */
    CONNECTING,
    /** The channel can send and receive messages. */
    OPEN,
    /** The channel is closing. */
    CLOSING,
    /** The channel is closed. */
    CLOSED
}
