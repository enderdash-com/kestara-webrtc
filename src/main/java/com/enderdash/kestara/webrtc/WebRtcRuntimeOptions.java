package com.enderdash.kestara.webrtc;

import java.time.Duration;
import java.util.Objects;

/**
 * Options that control one native WebRTC runtime.
 *
 * @param workerThreads the number of Rust worker threads
 * @param reactorThreads the number of runtime-owned single-threaded reactors
 * @param shutdownTimeout the maximum native shutdown duration
 * @param certificate an imported certificate, or {@code null} to generate one
 * @param sharedSockets runtime-owned shared socket settings, or {@code null} for per-peer sockets
 */
public record WebRtcRuntimeOptions(
        int workerThreads,
        int reactorThreads,
        Duration shutdownTimeout,
        DtlsCertificate certificate,
        SharedSocketOptions sharedSockets) {
    /** Default runtime options. */
    public static final WebRtcRuntimeOptions DEFAULT =
            new WebRtcRuntimeOptions(2, 1, Duration.ofSeconds(5), null, null);

    /**
     * Validates the runtime options.
     *
     * @param workerThreads the number of Rust worker threads
     * @param reactorThreads the number of runtime-owned reactors
     * @param shutdownTimeout the maximum native shutdown duration
     * @param certificate an imported certificate, or {@code null}
     * @param sharedSockets shared socket settings, or {@code null}
     */
    public WebRtcRuntimeOptions {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("Worker thread count must be at least one");
        }
        if (reactorThreads < 1 || reactorThreads > 1_024) {
            throw new IllegalArgumentException("Reactor thread count must be between 1 and 1024");
        }
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("Shutdown timeout must be at least one millisecond");
        }
    }

    /**
     * Returns a copy with the specified Rust worker count.
     *
     * @param value the worker count
     * @return the updated options
     */
    public WebRtcRuntimeOptions withWorkerThreads(int value) {
        return new WebRtcRuntimeOptions(value, reactorThreads, shutdownTimeout, certificate, sharedSockets);
    }

    /** Returns a copy with the specified runtime-owned reactor count.
     * @param value the reactor count
     * @return the updated options
     */
    public WebRtcRuntimeOptions withReactorThreads(int value) {
        return new WebRtcRuntimeOptions(workerThreads, value, shutdownTimeout, certificate, sharedSockets);
    }

    /**
     * Returns a copy with the specified shutdown timeout.
     *
     * @param value the shutdown timeout
     * @return the updated options
     */
    public WebRtcRuntimeOptions withShutdownTimeout(Duration value) {
        return new WebRtcRuntimeOptions(workerThreads, reactorThreads, value, certificate, sharedSockets);
    }

    /** Returns a copy that imports the specified runtime certificate.
     * @param value the certificate
     * @return the updated options
     */
    public WebRtcRuntimeOptions withCertificate(DtlsCertificate value) {
        return new WebRtcRuntimeOptions(
                workerThreads,
                reactorThreads,
                shutdownTimeout,
                Objects.requireNonNull(value, "value"),
                sharedSockets);
    }

    /** Returns a copy with generated certificate material.
     * @return the updated options
     */
    public WebRtcRuntimeOptions withGeneratedCertificate() {
        return new WebRtcRuntimeOptions(workerThreads, reactorThreads, shutdownTimeout, null, sharedSockets);
    }

    /** Returns a copy with runtime-owned shared sockets.
     * @param value the shared socket options
     * @return the updated options
     */
    public WebRtcRuntimeOptions withSharedSockets(SharedSocketOptions value) {
        return new WebRtcRuntimeOptions(
                workerThreads,
                reactorThreads,
                shutdownTimeout,
                certificate,
                Objects.requireNonNull(value, "value"));
    }

    /** Returns a copy that gives each peer its own sockets.
     * @return the updated options
     */
    public WebRtcRuntimeOptions withoutSharedSockets() {
        return new WebRtcRuntimeOptions(workerThreads, reactorThreads, shutdownTimeout, certificate, null);
    }
}
