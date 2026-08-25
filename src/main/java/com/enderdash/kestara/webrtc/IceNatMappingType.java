package com.enderdash.kestara.webrtc;

/** Controls how ICE advertises addresses from a one-to-one NAT mapping. */
public enum IceNatMappingType {
    /** Replace local host addresses with mapped public addresses. */
    HOST,
    /** Add mapped public addresses as server-reflexive candidates. */
    SERVER_REFLEXIVE
}
