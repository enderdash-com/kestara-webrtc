package com.enderdash.kestara.webrtc;

/** Diagnostics for one ICE candidate in the selected pair. */
public record IceCandidateStats(
        String id,
        String address,
        int port,
        String protocol,
        String candidateType,
        long priority,
        String url,
        String relayProtocol,
        String foundation,
        String relatedAddress,
        int relatedPort,
        String usernameFragment,
        String tcpType) {}
