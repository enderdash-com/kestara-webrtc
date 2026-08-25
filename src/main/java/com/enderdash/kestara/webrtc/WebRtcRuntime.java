package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBindings;
import com.enderdash.kestara.webrtc.internal.NativeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongConsumer;

/** Owns an isolated native WebRTC executor, handle registry, and event loop. */
public final class WebRtcRuntime implements AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(WebRtcRuntime.class.getName());
    private static final long EVENT_POLL_MILLIS = 1_000;

    private final long handle;
    private final WebRtcRuntimeOptions options;
    private final String certificateFingerprint;
    private final AtomicLong nextOperation = new AtomicLong(1);
    private final ConcurrentMap<Long, PendingOperation<?>> operations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, PeerConnection> peers = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final Thread eventThread;
    private volatile boolean closing;
    private volatile boolean closed;
    private volatile long shutdownOperation;

    private WebRtcRuntime(long handle, WebRtcRuntimeOptions options) {
        this.handle = handle;
        this.options = options;
        certificateFingerprint = Objects.requireNonNull(
                NativeBindings.nativeRuntimeCertificateFingerprint(handle),
                "Native DTLS certificate fingerprint");
        eventThread = new Thread(this::dispatchEvents, "kestara-webrtc-events-" + handle);
        eventThread.setDaemon(true);
        eventThread.setContextClassLoader(null);
        eventThread.start();
    }

    /**
     * Creates a runtime with default options.
     *
     * @return the runtime
     */
    public static WebRtcRuntime create() {
        return create(WebRtcRuntimeOptions.DEFAULT);
    }

    /**
     * Creates an isolated native WebRTC runtime.
     *
     * @param options the runtime options
     * @return the runtime
     */
    public static WebRtcRuntime create(WebRtcRuntimeOptions options) {
        Objects.requireNonNull(options, "options");
        KestaraWebRtc.ensureNativeAbi();
        long handle = NativeBindings.nativeCreateRuntime(options.workerThreads());
        try {
            return new WebRtcRuntime(handle, options);
        } catch (RuntimeException | Error error) {
            try {
                NativeBindings.nativeReleaseRuntime(handle);
            } catch (RuntimeException | Error releaseError) {
                error.addSuppressed(releaseError);
            }
            throw error;
        }
    }

    /**
     * Returns this runtime's immutable options.
     *
     * @return the runtime options
     */
    public WebRtcRuntimeOptions options() {
        return options;
    }

    /**
     * Returns the SHA-256 fingerprint of the DTLS certificate owned by this runtime.
     *
     * @return the lowercase, colon-separated fingerprint
     */
    public String certificateFingerprint() {
        return certificateFingerprint;
    }

    /**
     * Returns current runtime counters and lifecycle state.
     *
     * @return a diagnostics snapshot
     */
    public WebRtcRuntimeDiagnostics diagnostics() {
        int channels = peers.values().stream()
                .mapToInt(PeerConnection::dataChannelCount)
                .sum();
        return new WebRtcRuntimeDiagnostics(
                options.workerThreads(),
                peers.size(),
                channels,
                operations.size(),
                closing,
                closed);
    }

    /**
     * Creates a peer connection asynchronously.
     *
     * @param configuration the peer configuration
     * @return a stage that completes with the peer
     */
    public CompletionStage<PeerConnection> createPeerConnectionAsync(
            PeerConnectionConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        NativeConfiguration nativeConfiguration = NativeConfiguration.from(configuration);
        IceOptions ice = configuration.iceOptions();
        SctpOptions sctp = configuration.sctpOptions();
        IceNatMapping natMapping = ice.natMapping().orElse(null);
        return submit(
                event -> {
                    PeerConnection peer = new PeerConnection(this, event.peerHandle(), configuration);
                    peers.put(event.peerHandle(), peer);
                    return peer;
                },
                operation -> NativeBindings.nativeSubmitCreatePeer(
                        handle,
                        operation,
                        nativeConfiguration.urls,
                        nativeConfiguration.usernames,
                        nativeConfiguration.credentials,
                        configuration.minPort(),
                        configuration.maxPort(),
                        configuration.iceTransportPolicy().ordinal(),
                        optionalMillis(ice.disconnectedTimeout()),
                        optionalMillis(ice.failedTimeout()),
                        optionalMillis(ice.keepAliveInterval()),
                        optionalMillis(ice.checkInterval()),
                        ice.maxBindingRequests().orElse(-1),
                        optionalMillis(ice.hostAcceptanceMinWait()),
                        optionalMillis(ice.serverReflexiveAcceptanceMinWait()),
                        optionalMillis(ice.peerReflexiveAcceptanceMinWait()),
                        optionalMillis(ice.relayAcceptanceMinWait()),
                        networkTypeMask(ice),
                        ice.mdnsMode().ordinal(),
                        optionalMillis(ice.mdnsQueryTimeout()),
                        ice.lite(),
                        natMapping == null
                                ? new String[0]
                                : natMapping.externalAddresses().toArray(String[]::new),
                        natMapping == null ? -1 : natMapping.type().ordinal(),
                        ice.discardLocalCandidatesOnRestart(),
                        ice.candidatePoolSize(),
                        sctp.sendBufferLimit(),
                        sctp.receiveBufferSize(),
                        sctp.maximumMessageSize(),
                        configuration.operationTimeoutMillis()));
    }

    /**
     * Creates a peer connection and waits up to its configured operation timeout.
     *
     * @param configuration the peer configuration
     * @return the peer connection
     */
    public PeerConnection createPeerConnection(PeerConnectionConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return await(createPeerConnectionAsync(configuration), configuration.operationTimeoutMillis());
    }

    /**
     * Stops this runtime without blocking the caller.
     *
     * <p>The runtime gives accepted commands time to finish, closes all peers, and stops its native
     * worker threads. It cancels remaining work when the shutdown timeout expires.
     *
     * @return the shared shutdown stage
     */
    public CompletionStage<Void> closeAsync() {
        synchronized (lifecycleLock) {
            if (closing || closed) {
                return closeFuture;
            }
            closing = true;
            long operation = nextOperation.getAndIncrement();
            shutdownOperation = operation;
            operations.put(operation, new PendingOperation<>(closeFuture, ignored -> null));
            try {
                NativeBindings.nativeSubmitCloseRuntime(
                        handle, operation, options.shutdownTimeout().toMillis());
            } catch (RuntimeException | Error error) {
                operations.remove(operation);
                completeShutdown(error);
            }
            return closeFuture;
        }
    }

    /** Stops this runtime and waits for all native worker threads to exit. */
    @Override
    public void close() {
        await(closeAsync(), shutdownWaitMillis());
        joinEventThread();
    }

    CompletionStage<String> createDescriptionAsync(
            long peer, SessionDescriptionType type, long timeoutMillis) {
        return submit(
                event -> Objects.requireNonNull(event.text(), "Native SDP result"),
                operation -> NativeBindings.nativeSubmitCreateDescription(
                        handle, operation, peer, type.ordinal(), timeoutMillis));
    }

    CompletionStage<Void> setLocalDescriptionAsync(
            long peer, SessionDescription description, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitSetLocalDescription(
                        handle,
                        operation,
                        peer,
                        description.sdp(),
                        description.type().ordinal(),
                        timeoutMillis));
    }

    CompletionStage<Void> setRemoteDescriptionAsync(
            long peer, SessionDescription description, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitSetRemoteDescription(
                        handle,
                        operation,
                        peer,
                        description.sdp(),
                        description.type().ordinal(),
                        timeoutMillis));
    }

    CompletionStage<Void> addIceCandidateAsync(
            long peer, IceCandidate candidate, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitAddIceCandidate(
                        handle,
                        operation,
                        peer,
                        candidate.candidate(),
                        candidate.sdpMid(),
                        candidate.sdpMLineIndex() == null ? -1 : candidate.sdpMLineIndex(),
                        timeoutMillis));
    }

    CompletionStage<Void> restartIceAsync(long peer, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitRestartIce(
                        handle, operation, peer, timeoutMillis));
    }

    CompletionStage<Void> setConfigurationAsync(
            long peer, PeerConnectionConfiguration configuration, long timeoutMillis) {
        NativeConfiguration nativeConfiguration = NativeConfiguration.from(configuration);
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitSetConfiguration(
                        handle,
                        operation,
                        peer,
                        nativeConfiguration.urls,
                        nativeConfiguration.usernames,
                        nativeConfiguration.credentials,
                        configuration.iceTransportPolicy().ordinal(),
                        timeoutMillis));
    }

    CompletionStage<Long> createDataChannelAsync(
            long peer, String label, DataChannelOptions channelOptions, long timeoutMillis) {
        return submit(
                NativeEvent::channelHandle,
                operation -> NativeBindings.nativeSubmitCreateDataChannel(
                        handle,
                        operation,
                        peer,
                        label,
                        channelOptions.ordered(),
                        optionalUnsigned16(channelOptions.maxPacketLifeTime()),
                        optionalUnsigned16(channelOptions.maxRetransmits()),
                        channelOptions.protocol(),
                        optionalUnsigned16(channelOptions.negotiatedId()),
                        timeoutMillis));
    }

    CompletionStage<Void> sendTextAsync(long channel, String text, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitSendDataChannelText(
                        handle, operation, channel, text, timeoutMillis));
    }

    CompletionStage<Void> sendBinaryAsync(long channel, byte[] data, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitSendDataChannelBinary(
                        handle, operation, channel, data, timeoutMillis));
    }

    CompletionStage<Void> closeDataChannelAsync(long channel, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitCloseDataChannel(
                        handle, operation, channel, timeoutMillis));
    }

    CompletionStage<Void> closePeerAsync(long peer, long timeoutMillis) {
        return submit(
                ignored -> null,
                operation -> NativeBindings.nativeSubmitClosePeer(
                        handle, operation, peer, timeoutMillis));
    }

    void unregisterPeer(long peer, PeerConnection connection) {
        peers.remove(peer, connection);
    }

    <T> T await(CompletionStage<T> stage, long timeoutMillis) {
        CompletableFuture<T> future = stage.toCompletableFuture();
        if (Thread.currentThread() == eventThread && !future.isDone()) {
            throw new IllegalStateException(
                    "A synchronous WebRTC operation cannot run on the native event thread");
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WebRtcException("Interrupted while waiting for a WebRTC operation", error);
        } catch (TimeoutException error) {
            throw new WebRtcException("Timed out while waiting for a WebRTC operation", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new CompletionException(cause);
        }
    }

    private <T> CompletionStage<T> submit(
            Function<NativeEvent, T> decoder, LongConsumer nativeSubmission) {
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            if (closing || closed) {
                future.completeExceptionally(new IllegalStateException("WebRtcRuntime is closed"));
                return future;
            }
            long operation = nextOperation.getAndIncrement();
            operations.put(operation, new PendingOperation<>(future, decoder));
            try {
                nativeSubmission.accept(operation);
            } catch (RuntimeException | Error error) {
                operations.remove(operation);
                future.completeExceptionally(error);
            }
        }
        return future;
    }

    private void dispatchEvents() {
        try {
            while (!closed) {
                NativeEvent event = NativeBindings.nativePollRuntimeEvent(handle, EVENT_POLL_MILLIS);
                if (event == null) {
                    continue;
                }
                if (event.kind() == NativeBindings.EVENT_OPERATION_COMPLETE) {
                    handleOperationCompletion(event);
                } else {
                    PeerConnection peer = peers.get(event.peerHandle());
                    if (peer != null) {
                        peer.handleNativeEvent(event);
                    }
                }
            }
        } catch (RuntimeException | Error error) {
            LOGGER.log(System.Logger.Level.ERROR, "Kestara WebRTC event dispatch stopped", error);
            completeShutdown(error);
        }
    }

    private void handleOperationCompletion(NativeEvent event) {
        PendingOperation<?> operation = operations.remove(event.operationHandle());
        if (event.operationHandle() == shutdownOperation) {
            Throwable failure = event.number() == 0
                    ? null
                    : new WebRtcException(errorMessage(event));
            completeShutdown(failure);
            return;
        }
        if (operation == null) {
            return;
        }
        if (event.number() == 0) {
            operation.complete(event);
        } else {
            operation.future.completeExceptionally(new WebRtcException(errorMessage(event)));
        }
    }

    private void completeShutdown(Throwable failure) {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closing = true;
            for (PeerConnection peer : peers.values().toArray(PeerConnection[]::new)) {
                peer.closeForRuntimeShutdown();
            }
            peers.clear();
            for (PendingOperation<?> operation : operations.values()) {
                if (operation.future != closeFuture) {
                    operation.future.completeExceptionally(
                            new IllegalStateException("WebRtcRuntime closed before the operation completed"));
                }
            }
            operations.clear();
            try {
                NativeBindings.nativeReleaseRuntime(handle);
            } catch (RuntimeException | Error releaseError) {
                if (failure == null) {
                    failure = releaseError;
                } else {
                    failure.addSuppressed(releaseError);
                }
            }
            closed = true;
            if (failure == null) {
                closeFuture.complete(null);
            } else {
                closeFuture.completeExceptionally(failure);
            }
        }
    }

    private void joinEventThread() {
        if (Thread.currentThread() == eventThread) {
            return;
        }
        try {
            eventThread.join(options.shutdownTimeout().toMillis());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WebRtcException("Interrupted while stopping the WebRTC event thread", error);
        }
        if (eventThread.isAlive()) {
            throw new WebRtcException("The WebRTC event thread did not stop");
        }
    }

    private long shutdownWaitMillis() {
        long timeoutMillis = options.shutdownTimeout().toMillis();
        return timeoutMillis > Long.MAX_VALUE - EVENT_POLL_MILLIS
                ? Long.MAX_VALUE
                : timeoutMillis + EVENT_POLL_MILLIS;
    }

    private static String errorMessage(NativeEvent event) {
        return event.secondaryText() == null ? "WebRTC operation failed" : event.secondaryText();
    }

    private static int optionalUnsigned16(Integer value) {
        return value == null ? -1 : value;
    }

    private static long optionalMillis(java.util.Optional<java.time.Duration> value) {
        return value.map(java.time.Duration::toMillis).orElse(-1L);
    }

    private static int networkTypeMask(IceOptions options) {
        int mask = 0;
        for (IceNetworkType type : options.networkTypes()) {
            mask |= 1 << type.ordinal();
        }
        return mask;
    }

    private record PendingOperation<T>(
            CompletableFuture<T> future, Function<NativeEvent, T> decoder) {
        private void complete(NativeEvent event) {
            try {
                future.complete(decoder.apply(event));
            } catch (RuntimeException | Error error) {
                future.completeExceptionally(error);
            }
        }
    }

    private record NativeConfiguration(String[] urls, String[] usernames, String[] credentials) {
        private static NativeConfiguration from(PeerConnectionConfiguration configuration) {
            List<String> urls = new ArrayList<>();
            List<String> usernames = new ArrayList<>();
            List<String> credentials = new ArrayList<>();
            for (IceServer server : configuration.iceServers()) {
                for (String url : server.urls()) {
                    urls.add(url);
                    usernames.add(server.username());
                    credentials.add(server.credential());
                }
            }
            return new NativeConfiguration(
                    urls.toArray(String[]::new),
                    usernames.toArray(String[]::new),
                    credentials.toArray(String[]::new));
        }
    }
}
