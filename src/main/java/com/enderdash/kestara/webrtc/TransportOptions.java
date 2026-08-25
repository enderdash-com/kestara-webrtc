package com.enderdash.kestara.webrtc;

import java.util.List;
import java.util.Objects;

/** Local transport bindings and inbound packet-size controls. */
public record TransportOptions(
        List<String> udpBindAddresses,
        List<String> tcpBindAddresses,
        int receiveMtu) {
    /** Bind addresses derived from the configured ICE network types and the backend MTU default. */
    public static final TransportOptions DEFAULT =
            new TransportOptions(List.of(), List.of(), 0);

    /**
     * Validates and copies the transport options.
     *
     * @param udpBindAddresses the UDP bind IP addresses
     * @param tcpBindAddresses the TCP bind IP addresses
     * @param receiveMtu the receive MTU, or zero for the backend default
     */
    public TransportOptions {
        udpBindAddresses = List.copyOf(Objects.requireNonNull(udpBindAddresses, "udpBindAddresses"));
        tcpBindAddresses = List.copyOf(Objects.requireNonNull(tcpBindAddresses, "tcpBindAddresses"));
        if (receiveMtu != 0 && receiveMtu < 576) {
            throw new IllegalArgumentException("Receive MTU must be zero or at least 576 bytes");
        }
    }

    /** Returns a copy with the specified receive MTU. Zero selects the backend default.
     * @param value the MTU in bytes
     * @return the updated options
     */
    public TransportOptions withReceiveMtu(int value) {
        return new TransportOptions(udpBindAddresses, tcpBindAddresses, value);
    }
}
