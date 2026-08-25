package com.enderdash.kestara.webrtc;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/** Immutable configuration for a peer connection. */
public final class PeerConnectionConfiguration {
    /** Default peer connection configuration. */
    public static final PeerConnectionConfiguration DEFAULT = new PeerConnectionConfiguration(
            List.of(),
            0,
            0,
            IceTransportPolicy.ALL,
            IceOptions.DEFAULT,
            SctpOptions.DEFAULT,
            ForkJoinPool.commonPool(),
            10_000);

    private final List<IceServer> iceServers;
    private final int minPort;
    private final int maxPort;
    private final IceTransportPolicy iceTransportPolicy;
    private final IceOptions iceOptions;
    private final SctpOptions sctpOptions;
    private final DtlsOptions dtlsOptions;
    private final TransportOptions transportOptions;
    private final Executor callbackExecutor;
    private final long operationTimeoutMillis;

    private PeerConnectionConfiguration(
            List<IceServer> iceServers,
            int minPort,
            int maxPort,
            IceTransportPolicy iceTransportPolicy,
            IceOptions iceOptions,
            SctpOptions sctpOptions,
            Executor callbackExecutor,
            long operationTimeoutMillis) {
        this(
                iceServers,
                minPort,
                maxPort,
                iceTransportPolicy,
                iceOptions,
                sctpOptions,
                DtlsOptions.DEFAULT,
                TransportOptions.DEFAULT,
                callbackExecutor,
                operationTimeoutMillis);
    }

    private PeerConnectionConfiguration(
            List<IceServer> iceServers,
            int minPort,
            int maxPort,
            IceTransportPolicy iceTransportPolicy,
            IceOptions iceOptions,
            SctpOptions sctpOptions,
            DtlsOptions dtlsOptions,
            TransportOptions transportOptions,
            Executor callbackExecutor,
            long operationTimeoutMillis) {
        this.iceServers = List.copyOf(iceServers);
        this.minPort = minPort;
        this.maxPort = maxPort;
        this.iceTransportPolicy = iceTransportPolicy;
        this.iceOptions = iceOptions;
        this.sctpOptions = sctpOptions;
        this.dtlsOptions = dtlsOptions;
        this.transportOptions = transportOptions;
        this.callbackExecutor = callbackExecutor;
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    /**
     * Returns the ICE servers.
     *
     * @return the immutable ICE server list
     */
    public List<IceServer> iceServers() {
        return iceServers;
    }

    /**
     * Returns the first allowed transport port.
     *
     * @return the port, or {@code 0} when no range is set
     */
    public int minPort() {
        return minPort;
    }

    /**
     * Returns the last allowed transport port.
     *
     * @return the port, or {@code 0} when no range is set
     */
    public int maxPort() {
        return maxPort;
    }

    /**
     * Returns the ICE transport policy.
     *
     * @return the policy
     */
    public IceTransportPolicy iceTransportPolicy() {
        return iceTransportPolicy;
    }

    /**
     * Returns the advanced ICE options.
     *
     * @return the ICE options
     */
    public IceOptions iceOptions() {
        return iceOptions;
    }

    /**
     * Returns the SCTP transport options.
     *
     * @return the SCTP options
     */
    public SctpOptions sctpOptions() {
        return sctpOptions;
    }

    /** Returns the DTLS negotiation options.
     * @return the DTLS options
     */
    public DtlsOptions dtlsOptions() {
        return dtlsOptions;
    }

    /** Returns local bind-address and MTU options.
     * @return the transport options
     */
    public TransportOptions transportOptions() {
        return transportOptions;
    }

    /**
     * Returns the application callback executor.
     *
     * @return the callback executor
     */
    public Executor callbackExecutor() {
        return callbackExecutor;
    }

    /**
     * Returns the bound for synchronous native operations.
     *
     * @return the timeout in milliseconds
     */
    public long operationTimeoutMillis() {
        return operationTimeoutMillis;
    }

    /**
     * Returns a copy with the specified ICE servers.
     *
     * @param value the ICE servers
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withIceServers(List<IceServer> value) {
        return copy(List.copyOf(Objects.requireNonNull(value, "value")), minPort, maxPort,
                iceTransportPolicy, iceOptions, sctpOptions, callbackExecutor, operationTimeoutMillis);
    }

    /**
     * Returns a copy that restricts transport sockets to an inclusive port range.
     *
     * @param minimum the first port
     * @param maximum the last port
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withPortRange(int minimum, int maximum) {
        if (minimum < 1 || maximum > 65_535 || minimum > maximum) {
            throw new IllegalArgumentException("Port range must be between 1 and 65535");
        }
        return copy(iceServers, minimum, maximum, iceTransportPolicy, iceOptions, sctpOptions,
                callbackExecutor, operationTimeoutMillis);
    }

    /**
     * Returns a copy without a transport port restriction.
     *
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withoutPortRange() {
        return copy(iceServers, 0, 0, iceTransportPolicy, iceOptions, sctpOptions,
                callbackExecutor, operationTimeoutMillis);
    }

    /**
     * Returns a copy with the specified ICE transport policy.
     *
     * @param value the policy
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withIceTransportPolicy(IceTransportPolicy value) {
        return copy(iceServers, minPort, maxPort, Objects.requireNonNull(value, "value"),
                iceOptions, sctpOptions, callbackExecutor, operationTimeoutMillis);
    }

    /**
     * Returns a copy with the specified advanced ICE options.
     *
     * @param value the ICE options
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withIceOptions(IceOptions value) {
        return copy(iceServers, minPort, maxPort, iceTransportPolicy,
                Objects.requireNonNull(value, "value"), sctpOptions, callbackExecutor, operationTimeoutMillis);
    }

    /**
     * Returns a copy with the specified SCTP transport options.
     *
     * @param value the SCTP options
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withSctpOptions(SctpOptions value) {
        return copy(iceServers, minPort, maxPort, iceTransportPolicy, iceOptions,
                Objects.requireNonNull(value, "value"), callbackExecutor, operationTimeoutMillis);
    }

    /** Returns a copy with the specified DTLS options.
     * @param value the DTLS options
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withDtlsOptions(DtlsOptions value) {
        return new PeerConnectionConfiguration(
                iceServers,
                minPort,
                maxPort,
                iceTransportPolicy,
                iceOptions,
                sctpOptions,
                Objects.requireNonNull(value, "value"),
                transportOptions,
                callbackExecutor,
                operationTimeoutMillis);
    }

    /** Returns a copy with the specified transport options.
     * @param value the transport options
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withTransportOptions(TransportOptions value) {
        return new PeerConnectionConfiguration(
                iceServers,
                minPort,
                maxPort,
                iceTransportPolicy,
                iceOptions,
                sctpOptions,
                dtlsOptions,
                Objects.requireNonNull(value, "value"),
                callbackExecutor,
                operationTimeoutMillis);
    }

    /**
     * Returns a copy that sends application callbacks to the specified executor.
     *
     * @param value the executor
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withCallbackExecutor(Executor value) {
        return copy(iceServers, minPort, maxPort, iceTransportPolicy,
                iceOptions, sctpOptions, Objects.requireNonNull(value, "value"),
                operationTimeoutMillis);
    }

    /**
     * Returns a copy with a bound for synchronous native operations.
     *
     * @param timeout the timeout
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withOperationTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long millis = timeout.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException("Operation timeout must be at least one millisecond");
        }
        return copy(iceServers, minPort, maxPort, iceTransportPolicy,
                iceOptions, sctpOptions, callbackExecutor, millis);
    }

    private PeerConnectionConfiguration copy(
            List<IceServer> iceServers,
            int minPort,
            int maxPort,
            IceTransportPolicy iceTransportPolicy,
            IceOptions iceOptions,
            SctpOptions sctpOptions,
            Executor callbackExecutor,
            long operationTimeoutMillis) {
        return new PeerConnectionConfiguration(
                iceServers,
                minPort,
                maxPort,
                iceTransportPolicy,
                iceOptions,
                sctpOptions,
                dtlsOptions,
                transportOptions,
                callbackExecutor,
                operationTimeoutMillis);
    }
}
