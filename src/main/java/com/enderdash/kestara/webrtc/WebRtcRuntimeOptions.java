package com.enderdash.kestara.webrtc;

import java.time.Duration;
import java.util.Objects;

/**
 * Options that control one native WebRTC runtime.
 *
 * @param workerThreads the number of Rust worker threads
 * @param shutdownTimeout the maximum native shutdown duration
 */
public record WebRtcRuntimeOptions(int workerThreads, Duration shutdownTimeout) {
    /** Default runtime options. */
    public static final WebRtcRuntimeOptions DEFAULT =
            new WebRtcRuntimeOptions(2, Duration.ofSeconds(5));

    /**
     * Validates the runtime options.
     *
     * @param workerThreads the number of Rust worker threads
     * @param shutdownTimeout the maximum native shutdown duration
     */
    public WebRtcRuntimeOptions {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("Worker thread count must be at least one");
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
        return new WebRtcRuntimeOptions(value, shutdownTimeout);
    }

    /**
     * Returns a copy with the specified shutdown timeout.
     *
     * @param value the shutdown timeout
     * @return the updated options
     */
    public WebRtcRuntimeOptions withShutdownTimeout(Duration value) {
        return new WebRtcRuntimeOptions(workerThreads, value);
    }
}
