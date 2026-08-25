package com.enderdash.kestara.webrtc;

import java.time.Instant;
import java.util.List;

/** A point-in-time peer connection diagnostics snapshot. */
public record PeerConnectionStats(
        Instant timestamp,
        long dataChannelsOpened,
        long dataChannelsClosed,
        TransportStats transport,
        List<DataChannelStats> dataChannels) {
    /**
     * Copies the channel stats into an immutable list.
     *
     * @param timestamp the snapshot time
     * @param dataChannelsOpened the number of opened channels
     * @param dataChannelsClosed the number of closed channels
     * @param transport the transport stats
     * @param dataChannels the per-channel stats
     */
    public PeerConnectionStats {
        dataChannels = List.copyOf(dataChannels);
    }
}
