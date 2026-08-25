package com.enderdash.kestara.webrtc;

import java.util.List;
import java.util.Objects;

/** A list of public addresses for a one-to-one NAT. */
public record IceNatMapping(List<String> externalAddresses, IceNatMappingType type) {
    /**
     * Creates a NAT mapping.
     *
     * @param externalAddresses the public IPv4 or IPv6 addresses
     * @param type the candidate type to advertise
     */
    public IceNatMapping {
        externalAddresses = List.copyOf(Objects.requireNonNull(externalAddresses, "externalAddresses"));
        if (externalAddresses.isEmpty()
                || externalAddresses.stream().anyMatch(address -> address == null || address.isBlank())) {
            throw new IllegalArgumentException("A NAT mapping needs at least one external address");
        }
        Objects.requireNonNull(type, "type");
    }
}
