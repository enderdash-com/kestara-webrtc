package com.enderdash.kestara.webrtc;

/** A network family and transport that ICE can use for local candidates. */
public enum IceNetworkType {
    /** UDP over IPv4. */
    UDP4,
    /** UDP over IPv6. */
    UDP6,
    /** Passive TCP over IPv4. */
    TCP4,
    /** Passive TCP over IPv6. */
    TCP6
}
