package com.enderdash.kestara.webrtc;

import java.util.List;
import java.util.Objects;

/** Runtime-owned physical socket bindings shared by all peer connections. */
public record SharedSocketOptions(
        List<String> udpBindAddresses,
        List<String> tcpBindAddresses,
        int minPort,
        int maxPort) {
    /** A shared IPv4 UDP socket on an operating-system-selected port. */
    public static final SharedSocketOptions UDP4 =
            new SharedSocketOptions(List.of("0.0.0.0"), List.of(), 0, 0);

    /**
     * Validates and copies the socket options.
     *
     * @param udpBindAddresses the UDP bind IP addresses
     * @param tcpBindAddresses the TCP bind IP addresses
     * @param minPort the first port, or zero for an ephemeral port
     * @param maxPort the last port, or zero for an ephemeral port
     */
    public SharedSocketOptions {
        udpBindAddresses = List.copyOf(Objects.requireNonNull(udpBindAddresses, "udpBindAddresses"));
        tcpBindAddresses = List.copyOf(Objects.requireNonNull(tcpBindAddresses, "tcpBindAddresses"));
        if (udpBindAddresses.isEmpty() && tcpBindAddresses.isEmpty()) {
            throw new IllegalArgumentException("At least one UDP or TCP bind address is required");
        }
        udpBindAddresses.forEach(value -> requireAddress(value, "UDP"));
        tcpBindAddresses.forEach(value -> requireAddress(value, "TCP"));
        if (minPort < 0 || maxPort < 0 || minPort > 65_535 || maxPort > 65_535
                || (minPort == 0) != (maxPort == 0) || minPort > maxPort) {
            throw new IllegalArgumentException("Invalid shared socket port range");
        }
    }

    /** Returns a copy with the specified inclusive port range.
     * @param minimum the first port
     * @param maximum the last port
     * @return the updated options
     */
    public SharedSocketOptions withPortRange(int minimum, int maximum) {
        return new SharedSocketOptions(udpBindAddresses, tcpBindAddresses, minimum, maximum);
    }

    private static void requireAddress(String value, String protocol) {
        Objects.requireNonNull(value, protocol + " bind address");
        if (value.isBlank()) {
            throw new IllegalArgumentException(protocol + " bind address must not be blank");
        }
    }
}
