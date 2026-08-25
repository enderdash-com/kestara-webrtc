package com.enderdash.kestara.webrtc;

/** Diagnostics for the currently selected ICE candidate pair. */
public record IceCandidatePairStats(
        String id,
        IceCandidateStats localCandidate,
        IceCandidateStats remoteCandidate,
        long packetsSent,
        long packetsReceived,
        long bytesSent,
        long bytesReceived,
        double currentRoundTripTimeSeconds,
        double totalRoundTripTimeSeconds,
        long requestsSent,
        long requestsReceived,
        long responsesSent,
        long responsesReceived,
        String state,
        boolean nominated) {}
