package com.enderdash.alloy.webrtc;

/** Describes the state of local ICE candidate gathering. */
public enum IceGatheringState {
    /** Candidate gathering has not started. */
    NEW,
    /** Candidate gathering is active. */
    GATHERING,
    /** Candidate gathering is complete. */
    COMPLETE
}
