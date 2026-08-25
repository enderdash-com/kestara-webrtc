package com.enderdash.alloy.webrtc;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/** Immutable configuration for a peer connection. */
public final class PeerConnectionConfiguration {
    /** Default peer connection configuration. */
    public static final PeerConnectionConfiguration DEFAULT = new PeerConnectionConfiguration(
            List.of(), 0, 0, IceTransportPolicy.ALL, ForkJoinPool.commonPool(), 16 * 1024 * 1024, 10_000);

    private final List<IceServer> iceServers;
    private final int minPort;
    private final int maxPort;
    private final IceTransportPolicy iceTransportPolicy;
    private final Executor callbackExecutor;
    private final int dataChannelSendBufferLimit;
    private final long operationTimeoutMillis;

    private PeerConnectionConfiguration(
            List<IceServer> iceServers,
            int minPort,
            int maxPort,
            IceTransportPolicy iceTransportPolicy,
            Executor callbackExecutor,
            int dataChannelSendBufferLimit,
            long operationTimeoutMillis) {
        this.iceServers = List.copyOf(iceServers);
        this.minPort = minPort;
        this.maxPort = maxPort;
        this.iceTransportPolicy = iceTransportPolicy;
        this.callbackExecutor = callbackExecutor;
        this.dataChannelSendBufferLimit = dataChannelSendBufferLimit;
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
     * Returns the first allowed UDP port.
     *
     * @return the port, or {@code 0} when no range is set
     */
    public int minPort() {
        return minPort;
    }

    /**
     * Returns the last allowed UDP port.
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
     * Returns the application callback executor.
     *
     * @return the executor
     */
    public Executor callbackExecutor() {
        return callbackExecutor;
    }

    /**
     * Returns the native send-buffer limit for each DataChannel.
     *
     * @return the limit in bytes
     */
    public int dataChannelSendBufferLimit() {
        return dataChannelSendBufferLimit;
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
                iceTransportPolicy, callbackExecutor, dataChannelSendBufferLimit, operationTimeoutMillis);
    }

    /**
     * Returns a copy that restricts UDP sockets to an inclusive port range.
     *
     * @param minimum the first port
     * @param maximum the last port
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withPortRange(int minimum, int maximum) {
        if (minimum < 1 || maximum > 65_535 || minimum > maximum) {
            throw new IllegalArgumentException("Port range must be between 1 and 65535");
        }
        return copy(iceServers, minimum, maximum, iceTransportPolicy, callbackExecutor,
                dataChannelSendBufferLimit, operationTimeoutMillis);
    }

    /**
     * Returns a copy without a UDP port restriction.
     *
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withoutPortRange() {
        return copy(iceServers, 0, 0, iceTransportPolicy, callbackExecutor,
                dataChannelSendBufferLimit, operationTimeoutMillis);
    }

    /**
     * Returns a copy with the specified ICE transport policy.
     *
     * @param value the policy
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withIceTransportPolicy(IceTransportPolicy value) {
        return copy(iceServers, minPort, maxPort, Objects.requireNonNull(value, "value"),
                callbackExecutor, dataChannelSendBufferLimit, operationTimeoutMillis);
    }

    /**
     * Returns a copy that sends application callbacks to the specified executor.
     *
     * @param value the executor
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withCallbackExecutor(Executor value) {
        return copy(iceServers, minPort, maxPort, iceTransportPolicy,
                Objects.requireNonNull(value, "value"), dataChannelSendBufferLimit,
                operationTimeoutMillis);
    }

    /**
     * Returns a copy with a native send-buffer limit for each DataChannel.
     *
     * @param bytes the limit in bytes
     * @return the updated configuration
     */
    public PeerConnectionConfiguration withDataChannelSendBufferLimit(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Data channel send buffer limit must not be negative");
        }
        return copy(iceServers, minPort, maxPort, iceTransportPolicy, callbackExecutor, bytes,
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
        return copy(iceServers, minPort, maxPort, iceTransportPolicy, callbackExecutor,
                dataChannelSendBufferLimit, millis);
    }

    private static PeerConnectionConfiguration copy(
            List<IceServer> iceServers,
            int minPort,
            int maxPort,
            IceTransportPolicy iceTransportPolicy,
            Executor callbackExecutor,
            int dataChannelSendBufferLimit,
            long operationTimeoutMillis) {
        return new PeerConnectionConfiguration(iceServers, minPort, maxPort, iceTransportPolicy,
                callbackExecutor, dataChannelSendBufferLimit, operationTimeoutMillis);
    }
}
