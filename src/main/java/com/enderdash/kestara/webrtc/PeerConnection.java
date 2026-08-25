package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBindings;
import com.enderdash.kestara.webrtc.internal.NativeEvent;
import com.enderdash.kestara.webrtc.internal.SerialExecutor;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** A WebRTC peer connection for DataChannel negotiation and transport. */
public final class PeerConnection implements AutoCloseable {
    private static final Consumer<SessionDescription> NOOP_DESCRIPTION = ignored -> {};
    private static final Consumer<IceCandidate> NOOP_CANDIDATE = ignored -> {};
    private static final Consumer<DataChannel> NOOP_DATA_CHANNEL = ignored -> {};
    private static final Consumer<PeerConnectionState> NOOP_STATE = ignored -> {};
    private static final Consumer<IceConnectionState> NOOP_ICE_STATE = ignored -> {};
    private static final Consumer<IceGatheringState> NOOP_GATHERING_STATE = ignored -> {};

    private final WebRtcRuntime runtime;
    private final long handle;
    private final long operationTimeoutMillis;
    private final SerialExecutor callbacks;
    private final Map<Long, DataChannel> dataChannels = new ConcurrentHashMap<>();
    private final Object closeLock = new Object();
    private volatile boolean closing;
    private volatile boolean closed;
    private CompletableFuture<Void> closeFuture;
    private volatile PeerConnectionState state = PeerConnectionState.NEW;
    private volatile IceConnectionState iceConnectionState = IceConnectionState.NEW;
    private volatile IceGatheringState iceGatheringState = IceGatheringState.NEW;
    private volatile Consumer<SessionDescription> onLocalDescription = NOOP_DESCRIPTION;
    private volatile Consumer<IceCandidate> onLocalCandidate = NOOP_CANDIDATE;
    private volatile Consumer<DataChannel> onDataChannel = NOOP_DATA_CHANNEL;
    private volatile Consumer<PeerConnectionState> onStateChange = NOOP_STATE;
    private volatile Consumer<IceConnectionState> onIceConnectionStateChange = NOOP_ICE_STATE;
    private volatile Consumer<IceGatheringState> onIceGatheringStateChange = NOOP_GATHERING_STATE;

    PeerConnection(
            WebRtcRuntime runtime, long handle, PeerConnectionConfiguration configuration) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.handle = handle;
        operationTimeoutMillis = configuration.operationTimeoutMillis();
        callbacks = new SerialExecutor(configuration.callbackExecutor());
    }

    /**
     * Returns the aggregate peer state.
     *
     * @return the peer state
     */
    public PeerConnectionState state() {
        return state;
    }

    /**
     * Returns the ICE connectivity state.
     *
     * @return the ICE state
     */
    public IceConnectionState iceConnectionState() {
        return iceConnectionState;
    }

    /**
     * Returns the local candidate-gathering state.
     *
     * @return the gathering state
     */
    public IceGatheringState iceGatheringState() {
        return iceGatheringState;
    }

    /**
     * Sets the callback for locally applied descriptions.
     *
     * @param callback the callback
     */
    public void onLocalDescription(Consumer<SessionDescription> callback) {
        onLocalDescription = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the callback for trickled local ICE candidates.
     *
     * @param callback the callback
     */
    public void onLocalCandidate(Consumer<IceCandidate> callback) {
        onLocalCandidate = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the callback for channels created by the remote peer.
     *
     * @param callback the callback
     */
    public void onDataChannel(Consumer<DataChannel> callback) {
        onDataChannel = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the callback for aggregate peer-state changes.
     *
     * @param callback the callback
     */
    public void onStateChange(Consumer<PeerConnectionState> callback) {
        onStateChange = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the callback for ICE connectivity changes.
     *
     * @param callback the callback
     */
    public void onIceConnectionStateChange(Consumer<IceConnectionState> callback) {
        onIceConnectionStateChange = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Sets the callback for local candidate-gathering changes.
     *
     * @param callback the callback
     */
    public void onIceGatheringStateChange(Consumer<IceGatheringState> callback) {
        onIceGatheringStateChange = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Creates an SDP offer without applying it.
     *
     * @return a stage that completes with the offer
     */
    public CompletionStage<SessionDescription> createOfferAsync() {
        requireOpen();
        return runtime.createDescriptionAsync(
                        handle, SessionDescriptionType.OFFER, operationTimeoutMillis)
                .thenApply(sdp -> new SessionDescription(sdp, SessionDescriptionType.OFFER));
    }

    /**
     * Creates an SDP offer and waits up to the configured operation timeout.
     *
     * @return the offer
     */
    public SessionDescription createOffer() {
        return runtime.await(createOfferAsync(), operationTimeoutMillis);
    }

    /**
     * Creates an SDP answer without applying it.
     *
     * @return a stage that completes with the answer
     */
    public CompletionStage<SessionDescription> createAnswerAsync() {
        requireOpen();
        return runtime.createDescriptionAsync(
                        handle, SessionDescriptionType.ANSWER, operationTimeoutMillis)
                .thenApply(sdp -> new SessionDescription(sdp, SessionDescriptionType.ANSWER));
    }

    /**
     * Creates an SDP answer and waits up to the configured operation timeout.
     *
     * @return the answer
     */
    public SessionDescription createAnswer() {
        return runtime.await(createAnswerAsync(), operationTimeoutMillis);
    }

    /**
     * Applies a local session description without blocking the caller.
     *
     * @param description the description
     * @return the operation stage
     */
    public CompletionStage<Void> setLocalDescriptionAsync(SessionDescription description) {
        Objects.requireNonNull(description, "description");
        requireOpen();
        return runtime.setLocalDescriptionAsync(handle, description, operationTimeoutMillis)
                .thenRun(() -> callbacks.execute(() -> onLocalDescription.accept(description)));
    }

    /**
     * Applies a local session description and waits for completion.
     *
     * @param description the description
     */
    public void setLocalDescription(SessionDescription description) {
        runtime.await(setLocalDescriptionAsync(description), operationTimeoutMillis);
    }

    /**
     * Creates and applies an offer or answer without blocking the caller.
     *
     * @param type the description type
     * @return a stage that completes with the applied description
     */
    public CompletionStage<SessionDescription> setLocalDescriptionAsync(
            SessionDescriptionType type) {
        Objects.requireNonNull(type, "type");
        CompletionStage<SessionDescription> description = switch (type) {
            case OFFER -> createOfferAsync();
            case ANSWER -> createAnswerAsync();
            case PRANSWER, ROLLBACK -> throw new UnsupportedOperationException(
                    "Automatic local description does not support " + type);
        };
        return description.thenCompose(value ->
                setLocalDescriptionAsync(value).thenApply(ignored -> value));
    }

    /**
     * Creates and applies an offer or answer and waits for completion.
     *
     * @param type the description type
     * @return the applied description
     */
    public SessionDescription setLocalDescription(SessionDescriptionType type) {
        return runtime.await(setLocalDescriptionAsync(type), operationTimeoutMillis);
    }

    /**
     * Applies a remote session description without blocking the caller.
     *
     * @param description the description received through signaling
     * @return the operation stage
     */
    public CompletionStage<Void> setRemoteDescriptionAsync(SessionDescription description) {
        Objects.requireNonNull(description, "description");
        requireOpen();
        return runtime.setRemoteDescriptionAsync(handle, description, operationTimeoutMillis);
    }

    /**
     * Applies a remote session description and waits for completion.
     *
     * @param description the description received through signaling
     */
    public void setRemoteDescription(SessionDescription description) {
        runtime.await(setRemoteDescriptionAsync(description), operationTimeoutMillis);
    }

    /**
     * Adds a remote trickle ICE candidate without blocking the caller.
     *
     * @param candidate the candidate received through signaling
     * @return the operation stage
     */
    public CompletionStage<Void> addIceCandidateAsync(IceCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        requireOpen();
        return runtime.addIceCandidateAsync(handle, candidate, operationTimeoutMillis);
    }

    /**
     * Adds a remote trickle ICE candidate and waits for completion.
     *
     * @param candidate the candidate received through signaling
     */
    public void addIceCandidate(IceCandidate candidate) {
        runtime.await(addIceCandidateAsync(candidate), operationTimeoutMillis);
    }

    /**
     * Creates an ordered and reliable DataChannel without blocking the caller.
     *
     * @param label the channel label
     * @return a stage that completes with the channel
     */
    public CompletionStage<DataChannel> createDataChannelAsync(String label) {
        return createDataChannelAsync(label, DataChannelOptions.DEFAULT);
    }

    /**
     * Creates a DataChannel without blocking the caller.
     *
     * @param label the channel label
     * @param options the channel options
     * @return a stage that completes with the channel
     */
    public CompletionStage<DataChannel> createDataChannelAsync(
            String label, DataChannelOptions options) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(options, "options");
        requireOpen();
        return runtime.createDataChannelAsync(handle, label, options, operationTimeoutMillis)
                .thenApply(channelHandle -> {
                    DataChannel channel = new DataChannel(
                            runtime,
                            channelHandle,
                            label,
                            options.protocol(),
                            options.ordered(),
                            operationTimeoutMillis,
                            callbacks,
                            false,
                            () -> dataChannels.remove(channelHandle));
                    dataChannels.put(channelHandle, channel);
                    return channel;
                });
    }

    /**
     * Creates an ordered and reliable DataChannel and waits for completion.
     *
     * @param label the channel label
     * @return the channel
     */
    public DataChannel createDataChannel(String label) {
        return createDataChannel(label, DataChannelOptions.DEFAULT);
    }

    /**
     * Creates a DataChannel and waits for completion.
     *
     * @param label the channel label
     * @param options the channel options
     * @return the channel
     */
    public DataChannel createDataChannel(String label, DataChannelOptions options) {
        return runtime.await(createDataChannelAsync(label, options), operationTimeoutMillis);
    }

    /**
     * Closes the peer and all owned DataChannels without blocking the caller.
     *
     * @return the shared close stage
     */
    public CompletionStage<Void> closeAsync() {
        synchronized (closeLock) {
            if (closeFuture != null) {
                return closeFuture;
            }
            closing = true;
            closeFuture = new CompletableFuture<>();
            runtime.closePeerAsync(handle, operationTimeoutMillis)
                    .whenComplete((ignored, error) -> {
                        markClosed();
                        if (error == null) {
                            closeFuture.complete(null);
                        } else {
                            closeFuture.completeExceptionally(error);
                        }
                    });
            return closeFuture;
        }
    }

    /** Closes the peer and all owned DataChannels. */
    @Override
    public void close() {
        runtime.await(closeAsync(), operationTimeoutMillis);
    }

    int dataChannelCount() {
        return dataChannels.size();
    }

    void closeForRuntimeShutdown() {
        markClosed();
    }

    void handleNativeEvent(NativeEvent event) {
        try {
            switch (event.kind()) {
                case NativeBindings.EVENT_LOCAL_CANDIDATE -> {
                    Integer lineIndex = event.number() < 0 ? null : event.number();
                    IceCandidate candidate =
                            new IceCandidate(event.text(), event.secondaryText(), lineIndex);
                    callbacks.execute(() -> onLocalCandidate.accept(candidate));
                }
                case NativeBindings.EVENT_PEER_STATE -> {
                    PeerConnectionState newState = enumValue(
                            PeerConnectionState.values(), event.number(), "peer connection state");
                    state = newState;
                    callbacks.execute(() -> onStateChange.accept(newState));
                }
                case NativeBindings.EVENT_ICE_CONNECTION_STATE -> {
                    IceConnectionState newState = enumValue(
                            IceConnectionState.values(), event.number(), "ICE connection state");
                    iceConnectionState = newState;
                    callbacks.execute(() -> onIceConnectionStateChange.accept(newState));
                }
                case NativeBindings.EVENT_ICE_GATHERING_STATE -> {
                    IceGatheringState newState = enumValue(
                            IceGatheringState.values(), event.number(), "ICE gathering state");
                    iceGatheringState = newState;
                    callbacks.execute(() -> onIceGatheringStateChange.accept(newState));
                }
                case NativeBindings.EVENT_DATA_CHANNEL -> registerRemoteDataChannel(event);
                case NativeBindings.EVENT_DATA_CHANNEL_OPEN,
                        NativeBindings.EVENT_DATA_CHANNEL_CLOSING,
                        NativeBindings.EVENT_DATA_CHANNEL_CLOSED,
                        NativeBindings.EVENT_DATA_CHANNEL_ERROR,
                        NativeBindings.EVENT_DATA_CHANNEL_TEXT,
                        NativeBindings.EVENT_DATA_CHANNEL_BINARY -> routeDataChannelEvent(event);
                default -> throw new IllegalArgumentException(
                        "Unsupported peer event: " + event.kind());
            }
        } catch (RuntimeException error) {
            System.getLogger(PeerConnection.class.getName())
                    .log(System.Logger.Level.WARNING, "Failed to dispatch a WebRTC event", error);
        }
    }

    private void registerRemoteDataChannel(NativeEvent event) {
        boolean ordered = (event.number() & 1) != 0;
        boolean initiallyOpen = (event.number() & 2) != 0;
        long channelHandle = event.channelHandle();
        DataChannel channel = new DataChannel(
                runtime,
                channelHandle,
                event.text() == null ? "" : event.text(),
                event.secondaryText() == null ? "" : event.secondaryText(),
                ordered,
                operationTimeoutMillis,
                callbacks,
                initiallyOpen,
                () -> dataChannels.remove(channelHandle));
        dataChannels.put(channelHandle, channel);
        callbacks.execute(() -> {
            onDataChannel.accept(channel);
            if (initiallyOpen) {
                channel.notifyOpen();
            }
        });
    }

    private void routeDataChannelEvent(NativeEvent event) {
        DataChannel channel = dataChannels.get(event.channelHandle());
        if (channel != null) {
            channel.handleNativeEvent(event);
        }
    }

    private void markClosed() {
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closing = true;
            closed = true;
            for (DataChannel channel : dataChannels.values()) {
                channel.markClosed();
            }
            dataChannels.clear();
            state = PeerConnectionState.CLOSED;
            runtime.unregisterPeer(handle, this);
            if (closeFuture == null) {
                closeFuture = CompletableFuture.completedFuture(null);
            }
        }
    }

    private void requireOpen() {
        if (closing || closed) {
            throw new IllegalStateException("PeerConnection is closed");
        }
    }

    private static <T> T enumValue(T[] values, int ordinal, String name) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown " + name + ": " + ordinal);
        }
        return values[ordinal];
    }
}
