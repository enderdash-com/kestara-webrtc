package com.enderdash.kestara.webrtc;

/** Controls multicast DNS use for ICE host candidates. */
public enum IceMdnsMode {
    /** Do not resolve or publish multicast DNS candidates. */
    DISABLED,
    /** Resolve remote multicast DNS candidates, but publish local IP addresses directly. */
    QUERY_ONLY,
    /** Resolve remote names and publish local host candidates as multicast DNS names. */
    QUERY_AND_GATHER
}
