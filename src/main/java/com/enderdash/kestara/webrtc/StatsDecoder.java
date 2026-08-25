package com.enderdash.kestara.webrtc;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

final class StatsDecoder {
    private static final int FORMAT_VERSION = 1;

    private StatsDecoder() {}

    static PeerConnectionStats decode(byte[] data) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
            int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new WebRtcException("Unsupported native stats format: " + version);
            }
            Instant timestamp = Instant.ofEpochMilli(input.readLong());
            long opened = input.readLong();
            long closed = input.readLong();
            TransportStats transport = new TransportStats(
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    readString(input),
                    readString(input),
                    readString(input),
                    readString(input),
                    readString(input),
                    readString(input),
                    Integer.toUnsignedLong(input.readInt()),
                    input.readBoolean() ? Optional.of(readPair(input)) : Optional.empty());
            int channelCount = input.readInt();
            if (channelCount < 0 || channelCount > 65_535) {
                throw new IOException("Invalid native DataChannel stats count");
            }
            List<DataChannelStats> channels = new ArrayList<>(channelCount);
            for (int index = 0; index < channelCount; index++) {
                channels.add(new DataChannelStats(
                        Short.toUnsignedInt(input.readShort()),
                        readString(input),
                        readString(input),
                        readString(input),
                        Integer.toUnsignedLong(input.readInt()),
                        input.readLong(),
                        Integer.toUnsignedLong(input.readInt()),
                        input.readLong()));
            }
            if (input.available() != 0) {
                throw new WebRtcException("Native stats contain trailing data");
            }
            return new PeerConnectionStats(timestamp, opened, closed, transport, channels);
        } catch (IOException error) {
            throw new WebRtcException("Failed to decode native WebRTC stats", error);
        }
    }

    private static IceCandidatePairStats readPair(DataInputStream input) throws IOException {
        return new IceCandidatePairStats(
                readString(input),
                readCandidate(input),
                readCandidate(input),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readDouble(),
                input.readDouble(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                readString(input),
                input.readBoolean());
    }

    private static IceCandidateStats readCandidate(DataInputStream input) throws IOException {
        return new IceCandidateStats(
                readString(input),
                readString(input),
                Short.toUnsignedInt(input.readShort()),
                readString(input),
                readString(input),
                Integer.toUnsignedLong(input.readInt()),
                readString(input),
                readString(input),
                readString(input),
                readString(input),
                Short.toUnsignedInt(input.readShort()),
                readString(input),
                readString(input));
    }

    private static String readString(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > input.available()) {
            throw new IOException("Invalid native stats string length");
        }
        return new String(input.readNBytes(size), java.nio.charset.StandardCharsets.UTF_8);
    }
}
