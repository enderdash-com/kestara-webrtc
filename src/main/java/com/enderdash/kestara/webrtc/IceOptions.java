package com.enderdash.kestara.webrtc;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable advanced ICE options. */
public final class IceOptions {
    /** Default ICE options. */
    public static final IceOptions DEFAULT = new IceOptions(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Set.of(IceNetworkType.UDP4),
            IceMdnsMode.QUERY_ONLY,
            null,
            false,
            null,
            true,
            0);

    private final Duration disconnectedTimeout;
    private final Duration failedTimeout;
    private final Duration keepAliveInterval;
    private final Duration checkInterval;
    private final Integer maxBindingRequests;
    private final Duration hostAcceptanceMinWait;
    private final Duration serverReflexiveAcceptanceMinWait;
    private final Duration peerReflexiveAcceptanceMinWait;
    private final Duration relayAcceptanceMinWait;
    private final Set<IceNetworkType> networkTypes;
    private final IceMdnsMode mdnsMode;
    private final Duration mdnsQueryTimeout;
    private final boolean lite;
    private final IceNatMapping natMapping;
    private final boolean discardLocalCandidatesOnRestart;
    private final int candidatePoolSize;

    private IceOptions(
            Duration disconnectedTimeout,
            Duration failedTimeout,
            Duration keepAliveInterval,
            Duration checkInterval,
            Integer maxBindingRequests,
            Duration hostAcceptanceMinWait,
            Duration serverReflexiveAcceptanceMinWait,
            Duration peerReflexiveAcceptanceMinWait,
            Duration relayAcceptanceMinWait,
            Set<IceNetworkType> networkTypes,
            IceMdnsMode mdnsMode,
            Duration mdnsQueryTimeout,
            boolean lite,
            IceNatMapping natMapping,
            boolean discardLocalCandidatesOnRestart,
            int candidatePoolSize) {
        this.disconnectedTimeout = disconnectedTimeout;
        this.failedTimeout = failedTimeout;
        this.keepAliveInterval = keepAliveInterval;
        this.checkInterval = checkInterval;
        this.maxBindingRequests = maxBindingRequests;
        this.hostAcceptanceMinWait = hostAcceptanceMinWait;
        this.serverReflexiveAcceptanceMinWait = serverReflexiveAcceptanceMinWait;
        this.peerReflexiveAcceptanceMinWait = peerReflexiveAcceptanceMinWait;
        this.relayAcceptanceMinWait = relayAcceptanceMinWait;
        this.networkTypes = Set.copyOf(networkTypes);
        this.mdnsMode = mdnsMode;
        this.mdnsQueryTimeout = mdnsQueryTimeout;
        this.lite = lite;
        this.natMapping = natMapping;
        this.discardLocalCandidatesOnRestart = discardLocalCandidatesOnRestart;
        this.candidatePoolSize = candidatePoolSize;
    }

    /**
     * Returns the idle time before ICE enters the disconnected state.
     *
     * @return the timeout, or an empty value for the backend default
     */
    public Optional<Duration> disconnectedTimeout() {
        return Optional.ofNullable(disconnectedTimeout);
    }

    /**
     * Returns the time before a disconnected ICE transport enters the failed state.
     *
     * @return the timeout, or an empty value for the backend default
     */
    public Optional<Duration> failedTimeout() {
        return Optional.ofNullable(failedTimeout);
    }

    /**
     * Returns the interval for ICE keep-alive packets.
     *
     * @return the interval, or an empty value for the backend default
     */
    public Optional<Duration> keepAliveInterval() {
        return Optional.ofNullable(keepAliveInterval);
    }

    /**
     * Returns the interval between ICE binding requests.
     *
     * @return the interval, or an empty value for the backend default
     */
    public Optional<Duration> checkInterval() {
        return Optional.ofNullable(checkInterval);
    }

    /**
     * Returns the maximum binding requests for one candidate pair.
     *
     * @return the limit, or an empty value for the backend default
     */
    public Optional<Integer> maxBindingRequests() {
        return Optional.ofNullable(maxBindingRequests);
    }

    /**
     * Returns the minimum wait before ICE accepts a host candidate pair.
     *
     * @return the wait, or an empty value for the backend default
     */
    public Optional<Duration> hostAcceptanceMinWait() {
        return Optional.ofNullable(hostAcceptanceMinWait);
    }

    /**
     * Returns the minimum wait before ICE accepts a server-reflexive candidate pair.
     *
     * @return the wait, or an empty value for the backend default
     */
    public Optional<Duration> serverReflexiveAcceptanceMinWait() {
        return Optional.ofNullable(serverReflexiveAcceptanceMinWait);
    }

    /**
     * Returns the minimum wait before ICE accepts a peer-reflexive candidate pair.
     *
     * @return the wait, or an empty value for the backend default
     */
    public Optional<Duration> peerReflexiveAcceptanceMinWait() {
        return Optional.ofNullable(peerReflexiveAcceptanceMinWait);
    }

    /**
     * Returns the minimum wait before ICE accepts a relay candidate pair.
     *
     * @return the wait, or an empty value for the backend default
     */
    public Optional<Duration> relayAcceptanceMinWait() {
        return Optional.ofNullable(relayAcceptanceMinWait);
    }

    /**
     * Returns the network types that ICE can gather.
     *
     * @return the immutable network-type set
     */
    public Set<IceNetworkType> networkTypes() {
        return networkTypes;
    }

    /**
     * Returns the multicast DNS mode.
     *
     * @return the multicast DNS mode
     */
    public IceMdnsMode mdnsMode() {
        return mdnsMode;
    }

    /**
     * Returns the multicast DNS query timeout.
     *
     * @return the timeout, or an empty value for the backend default
     */
    public Optional<Duration> mdnsQueryTimeout() {
        return Optional.ofNullable(mdnsQueryTimeout);
    }

    /**
     * Returns whether this peer uses ICE Lite.
     *
     * @return {@code true} for ICE Lite
     */
    public boolean lite() {
        return lite;
    }

    /**
     * Returns the one-to-one NAT mapping.
     *
     * @return the NAT mapping, or an empty value when none is set
     */
    public Optional<IceNatMapping> natMapping() {
        return Optional.ofNullable(natMapping);
    }

    /**
     * Returns whether an ICE restart replaces local candidates and transport sockets.
     *
     * @return {@code true} when restarts replace local candidates and sockets
     */
    public boolean discardLocalCandidatesOnRestart() {
        return discardLocalCandidatesOnRestart;
    }

    /**
     * Returns {@code 1} when the bundled ICE transport gathers before negotiation.
     *
     * @return {@code 0} or {@code 1}
     */
    public int candidatePoolSize() {
        return candidatePoolSize;
    }

    /**
     * Returns a copy with the specified connection-state and keep-alive timeouts.
     *
     * @param disconnected the disconnected timeout
     * @param failed the failed timeout
     * @param keepAlive the keep-alive interval
     * @return the updated options
     */
    public IceOptions withTimeouts(
            Duration disconnected, Duration failed, Duration keepAlive) {
        return copy(
                positive(disconnected, "disconnected"),
                positive(failed, "failed"),
                positive(keepAlive, "keepAlive"),
                checkInterval,
                maxBindingRequests,
                hostAcceptanceMinWait,
                serverReflexiveAcceptanceMinWait,
                peerReflexiveAcceptanceMinWait,
                relayAcceptanceMinWait,
                networkTypes,
                mdnsMode,
                mdnsQueryTimeout,
                lite,
                natMapping,
                discardLocalCandidatesOnRestart,
                candidatePoolSize);
    }

    /**
     * Returns a copy with the specified binding-request interval and limit.
     *
     * @param interval the interval between binding requests
     * @param maximumBindingRequests the request limit for one candidate pair
     * @return the updated options
     */
    public IceOptions withConnectionAttempts(Duration interval, int maximumBindingRequests) {
        if (maximumBindingRequests < 1 || maximumBindingRequests > 65_535) {
            throw new IllegalArgumentException("Maximum binding requests must be between 1 and 65535");
        }
        return copy(
                disconnectedTimeout,
                failedTimeout,
                keepAliveInterval,
                positive(interval, "interval"),
                maximumBindingRequests,
                hostAcceptanceMinWait,
                serverReflexiveAcceptanceMinWait,
                peerReflexiveAcceptanceMinWait,
                relayAcceptanceMinWait,
                networkTypes,
                mdnsMode,
                mdnsQueryTimeout,
                lite,
                natMapping,
                discardLocalCandidatesOnRestart,
                candidatePoolSize);
    }

    /**
     * Returns a copy with the minimum acceptance wait for each candidate type.
     *
     * @param host the host-candidate wait
     * @param serverReflexive the server-reflexive wait
     * @param peerReflexive the peer-reflexive wait
     * @param relay the relay wait
     * @return the updated options
     */
    public IceOptions withAcceptanceWaits(
            Duration host,
            Duration serverReflexive,
            Duration peerReflexive,
            Duration relay) {
        return copy(
                disconnectedTimeout,
                failedTimeout,
                keepAliveInterval,
                checkInterval,
                maxBindingRequests,
                nonNegative(host, "host"),
                nonNegative(serverReflexive, "serverReflexive"),
                nonNegative(peerReflexive, "peerReflexive"),
                nonNegative(relay, "relay"),
                networkTypes,
                mdnsMode,
                mdnsQueryTimeout,
                lite,
                natMapping,
                discardLocalCandidatesOnRestart,
                candidatePoolSize);
    }

    /**
     * Returns a copy that gathers candidates for the specified network types.
     *
     * @param value the nonempty network-type set
     * @return the updated options
     */
    public IceOptions withNetworkTypes(Set<IceNetworkType> value) {
        Set<IceNetworkType> copy = Set.copyOf(Objects.requireNonNull(value, "value"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("At least one ICE network type is required");
        }
        return copy(
                disconnectedTimeout,
                failedTimeout,
                keepAliveInterval,
                checkInterval,
                maxBindingRequests,
                hostAcceptanceMinWait,
                serverReflexiveAcceptanceMinWait,
                peerReflexiveAcceptanceMinWait,
                relayAcceptanceMinWait,
                copy,
                mdnsMode,
                mdnsQueryTimeout,
                lite,
                natMapping,
                discardLocalCandidatesOnRestart,
                candidatePoolSize);
    }

    /**
     * Returns a copy with the specified multicast DNS mode and query timeout.
     *
     * @param mode the multicast DNS mode
     * @param queryTimeout the query timeout
     * @return the updated options
     */
    public IceOptions withMdns(IceMdnsMode mode, Duration queryTimeout) {
        return copy(
                disconnectedTimeout,
                failedTimeout,
                keepAliveInterval,
                checkInterval,
                maxBindingRequests,
                hostAcceptanceMinWait,
                serverReflexiveAcceptanceMinWait,
                peerReflexiveAcceptanceMinWait,
                relayAcceptanceMinWait,
                networkTypes,
                Objects.requireNonNull(mode, "mode"),
                positive(queryTimeout, "queryTimeout"),
                lite,
                natMapping,
                discardLocalCandidatesOnRestart,
                candidatePoolSize);
    }

    /**
     * Returns a copy that enables or disables ICE Lite.
     *
     * @param value {@code true} to use ICE Lite
     * @return the updated options
     */
    public IceOptions withLite(boolean value) {
        return copyWith(value, natMapping, discardLocalCandidatesOnRestart, candidatePoolSize);
    }

    /**
     * Returns a copy with a one-to-one NAT mapping.
     *
     * @param value the NAT mapping
     * @return the updated options
     */
    public IceOptions withNatMapping(IceNatMapping value) {
        return copyWith(lite, Objects.requireNonNull(value, "value"), discardLocalCandidatesOnRestart, candidatePoolSize);
    }

    /**
     * Returns a copy without a one-to-one NAT mapping.
     *
     * @return the updated options
     */
    public IceOptions withoutNatMapping() {
        return copyWith(lite, null, discardLocalCandidatesOnRestart, candidatePoolSize);
    }

    /**
     * Returns a copy that controls socket replacement during an ICE restart.
     *
     * @param value {@code true} to replace local candidates and sockets
     * @return the updated options
     */
    public IceOptions withDiscardLocalCandidatesOnRestart(boolean value) {
        return copyWith(lite, natMapping, value, candidatePoolSize);
    }

    /**
     * Returns a copy that enables or disables gathering before negotiation.
     *
     * @param value {@code 0} to disable the pool or {@code 1} to enable it
     * @return the updated options
     */
    public IceOptions withCandidatePoolSize(int value) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException("ICE candidate pool size must be 0 or 1");
        }
        return copyWith(lite, natMapping, discardLocalCandidatesOnRestart, value);
    }

    private IceOptions copyWith(
            boolean newLite,
            IceNatMapping newNatMapping,
            boolean newDiscardLocalCandidatesOnRestart,
            int newCandidatePoolSize) {
        return copy(
                disconnectedTimeout,
                failedTimeout,
                keepAliveInterval,
                checkInterval,
                maxBindingRequests,
                hostAcceptanceMinWait,
                serverReflexiveAcceptanceMinWait,
                peerReflexiveAcceptanceMinWait,
                relayAcceptanceMinWait,
                networkTypes,
                mdnsMode,
                mdnsQueryTimeout,
                newLite,
                newNatMapping,
                newDiscardLocalCandidatesOnRestart,
                newCandidatePoolSize);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static IceOptions copy(
            Duration disconnectedTimeout,
            Duration failedTimeout,
            Duration keepAliveInterval,
            Duration checkInterval,
            Integer maxBindingRequests,
            Duration hostAcceptanceMinWait,
            Duration serverReflexiveAcceptanceMinWait,
            Duration peerReflexiveAcceptanceMinWait,
            Duration relayAcceptanceMinWait,
            Set<IceNetworkType> networkTypes,
            IceMdnsMode mdnsMode,
            Duration mdnsQueryTimeout,
            boolean lite,
            IceNatMapping natMapping,
            boolean discardLocalCandidatesOnRestart,
            int candidatePoolSize) {
        return new IceOptions(
                disconnectedTimeout,
                failedTimeout,
                keepAliveInterval,
                checkInterval,
                maxBindingRequests,
                hostAcceptanceMinWait,
                serverReflexiveAcceptanceMinWait,
                peerReflexiveAcceptanceMinWait,
                relayAcceptanceMinWait,
                networkTypes,
                mdnsMode,
                mdnsQueryTimeout,
                lite,
                natMapping,
                discardLocalCandidatesOnRestart,
                candidatePoolSize);
    }
}
