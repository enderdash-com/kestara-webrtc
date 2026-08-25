package com.enderdash.kestara.webrtc;

import java.util.Optional;

/** ICE and DTLS transport counters and negotiated parameters. */
public record TransportStats(
        long packetsSent,
        long packetsReceived,
        long bytesSent,
        long bytesReceived,
        String iceRole,
        String iceState,
        String dtlsRole,
        String dtlsState,
        String tlsVersion,
        String dtlsCipher,
        long selectedCandidatePairChanges,
        Optional<IceCandidatePairStats> selectedCandidatePair) {}
