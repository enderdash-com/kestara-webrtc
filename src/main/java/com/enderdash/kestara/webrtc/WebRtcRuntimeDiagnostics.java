package com.enderdash.kestara.webrtc;

/**
 * A point-in-time view of one WebRTC runtime.
 *
 * @param workerThreads the configured Rust worker count
 * @param reactorThreads the configured runtime-owned reactor count
 * @param peerConnections the number of registered peers
 * @param dataChannels the number of registered DataChannels
 * @param pendingOperations the number of accepted operations without a result
 * @param closing whether shutdown has started
 * @param closed whether native shutdown has finished
 */
public record WebRtcRuntimeDiagnostics(
        int workerThreads,
        int reactorThreads,
        int peerConnections,
        int dataChannels,
        int pendingOperations,
        boolean closing,
        boolean closed) {}
