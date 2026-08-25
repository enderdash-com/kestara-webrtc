package com.enderdash.kestara.webrtc;

/** Message and byte counters for one DataChannel. */
public record DataChannelStats(
        int identifier,
        String label,
        String protocol,
        String state,
        long messagesSent,
        long bytesSent,
        long messagesReceived,
        long bytesReceived) {}
