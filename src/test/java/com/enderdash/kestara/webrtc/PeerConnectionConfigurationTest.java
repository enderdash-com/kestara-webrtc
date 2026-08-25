package com.enderdash.kestara.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PeerConnectionConfigurationTest {
    @Test
    void copiesIceServersAndPortRange() {
        IceServer server = IceServer.authenticated(
                "kestara-user", "kestara-secret", "turn:turn.example.com:3478");

        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withIceServers(List.of(server))
                .withPortRange(10_000, 10_010)
                .withOperationTimeout(Duration.ofSeconds(3));

        assertEquals(List.of(server), configuration.iceServers());
        assertEquals(10_000, configuration.minPort());
        assertEquals(10_010, configuration.maxPort());
        assertEquals(3_000, configuration.operationTimeoutMillis());
    }

    @Test
    void rejectsConflictingReliabilityLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataChannelOptions(true, 100, 2, "", null));
    }
}
