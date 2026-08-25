package com.enderdash.kestara.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Set;
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

    @Test
    void configuresAdvancedIceAndSctpOptions() {
        IceOptions ice = IceOptions.DEFAULT
                .withTimeouts(Duration.ofSeconds(8), Duration.ofSeconds(30), Duration.ofSeconds(3))
                .withConnectionAttempts(Duration.ofMillis(150), 9)
                .withNetworkTypes(Set.of(IceNetworkType.UDP4, IceNetworkType.TCP4))
                .withMdns(IceMdnsMode.DISABLED, Duration.ofSeconds(4))
                .withLite(true)
                .withNatMapping(new IceNatMapping(
                        List.of("203.0.113.10"), IceNatMappingType.HOST))
                .withCandidatePoolSize(1);
        SctpOptions sctp = SctpOptions.DEFAULT
                .withReceiveBufferSize(512 * 1024)
                .withMaximumMessageSize(128 * 1024);

        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withIceOptions(ice)
                .withSctpOptions(sctp);

        assertEquals(ice, configuration.iceOptions());
        assertEquals(sctp, configuration.sctpOptions());
        assertEquals(9, ice.maxBindingRequests().orElseThrow());
        assertEquals(Set.of(IceNetworkType.UDP4, IceNetworkType.TCP4), ice.networkTypes());
    }

    @Test
    void rejectsInvalidTransportLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IceOptions.DEFAULT.withNetworkTypes(Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> IceOptions.DEFAULT.withCandidatePoolSize(2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SctpOptions(1_024, 32_768, 65_536));
    }
}
