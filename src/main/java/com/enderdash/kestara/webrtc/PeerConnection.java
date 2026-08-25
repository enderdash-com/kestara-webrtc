package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBindings;
import com.enderdash.kestara.webrtc.internal.NativeEvent;
import com.enderdash.kestara.webrtc.internal.SerialExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A WebRTC peer connection for DataChannel negotiation and transport. */
public final class PeerConnection implements AutoCloseable {
    private static final Consumer<SessionDescription> NOOP_DESCRIPTION = ignored -> {};
    private static final Consumer<IceCandidate> NOOP_CANDIDATE = ignored -> {};
    private static final Consumer<DataChannel> NOOP_DATA_CHANNEL = ignored -> {};
    private static final Consumer<PeerConnectionState> NOOP_STATE = ignored -> {};
    private static final Consumer<IceConnectionState> NOOP_ICE_STATE = ignored -> {};
    private static final Consumer<IceGatheringState> NOOP_GATHERING_STATE = ignored -> {};

    private final long handle;
    private final long operationTimeoutMillis;
    private final SerialExecutor callbacks;
    private final Map<Long, DataChannel> dataChannels = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile PeerConnectionState state = PeerConnectionState.NEW;
    private volatile IceConnectionState iceConnectionState = IceConnectionState.NEW;
    private volatile IceGatheringState iceGatheringState = IceGatheringState.NEW;
    private volatile Consumer<SessionDescription> onLocalDescription = NOOP_DESCRIPTION;
    private volatile Consumer<IceCandidate> onLocalCandidate = NOOP_CANDIDATE;
    private volatile Consumer<DataChannel> onDataChannel = NOOP_DATA_CHANNEL;
    private volatile Consumer<PeerConnectionState> onStateChange = NOOP_STATE;
    private volatile Consumer<IceConnectionState> onIceConnectionStateChange = NOOP_ICE_STATE;
    private volatile Consumer<IceGatheringState> onIceGatheringStateChange = NOOP_GATHERING_STATE;

    private PeerConnection(long handle, PeerConnectionConfiguration configuration) {
        this.handle = handle;
        this.operationTimeoutMillis = configuration.operationTimeoutMillis();
        this.callbacks = new SerialExecutor(configuration.callbackExecutor());
    }

    /**
     * Creates a peer connection and starts the shared native runtime when necessary.
     *
     * @param configuration the peer configuration
     * @return the peer connection
     */
    public static PeerConnection create(PeerConnectionConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        NativeConfiguration nativeConfiguration = NativeConfiguration.from(configuration);
        long handle = NativeBindings.createPeer(
                nativeConfiguration.urls,
                nativeConfiguration.usernames,
                nativeConfiguration.credentials,
                configuration.minPort(),
                configuration.maxPort(),
                configuration.iceTransportPolicy().ordinal(),
                configuration.dataChannelSendBufferLimit(),
                configuration.operationTimeoutMillis());
        try {
            PeerConnection peer = new PeerConnection(handle, configuration);
            NativeEventDispatcher.register(handle, peer);
            return peer;
        } catch (RuntimeException | Error error) {
            try {
                NativeBindings.closePeer(handle, configuration.operationTimeoutMillis());
            } catch (RuntimeException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
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
     * @return the offer
     */
    public SessionDescription createOffer() {
        requireOpen();
        return new SessionDescription(
                NativeBindings.createDescription(handle, SessionDescriptionType.OFFER.ordinal()),
                SessionDescriptionType.OFFER);
    }

    /**
     * Creates an SDP answer without applying it.
     *
     * @return the answer
     */
    public SessionDescription createAnswer() {
        requireOpen();
        return new SessionDescription(
                NativeBindings.createDescription(handle, SessionDescriptionType.ANSWER.ordinal()),
                SessionDescriptionType.ANSWER);
    }

    /**
     * Applies a local session description and reports it to the local-description callback.
     *
     * @param description the description
     */
    public void setLocalDescription(SessionDescription description) {
        Objects.requireNonNull(description, "description");
        requireOpen();
        NativeBindings.setLocalDescription(
                handle, description.sdp(), description.type().ordinal(), operationTimeoutMillis);
        callbacks.execute(() -> onLocalDescription.accept(description));
    }

    /**
     * Creates and applies an offer or answer.
     *
     * @param type the description type
     * @return the applied description
     */
    public SessionDescription setLocalDescription(SessionDescriptionType type) {
        Objects.requireNonNull(type, "type");
        SessionDescription description = switch (type) {
            case OFFER -> createOffer();
            case ANSWER -> createAnswer();
            case PRANSWER, ROLLBACK -> throw new UnsupportedOperationException(
                    "Automatic local description does not support " + type);
        };
        setLocalDescription(description);
        return description;
    }

    /**
     * Applies a remote session description.
     *
     * @param description the description received through signaling
     */
    public void setRemoteDescription(SessionDescription description) {
        Objects.requireNonNull(description, "description");
        requireOpen();
        NativeBindings.setRemoteDescription(
                handle, description.sdp(), description.type().ordinal(), operationTimeoutMillis);
    }

    /**
     * Adds a remote trickle ICE candidate.
     *
     * @param candidate the candidate received through signaling
     */
    public void addIceCandidate(IceCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        requireOpen();
        NativeBindings.addIceCandidate(
                handle,
                candidate.candidate(),
                candidate.sdpMid(),
                candidate.sdpMLineIndex() == null ? -1 : candidate.sdpMLineIndex(),
                operationTimeoutMillis);
    }

    /**
     * Creates an ordered and reliable DataChannel.
     *
     * @param label the channel label
     * @return the channel
     */
    public DataChannel createDataChannel(String label) {
        return createDataChannel(label, DataChannelOptions.DEFAULT);
    }

    /**
     * Creates a DataChannel.
     *
     * @param label the channel label
     * @param options the channel options
     * @return the channel
     */
    public DataChannel createDataChannel(String label, DataChannelOptions options) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(options, "options");
        requireOpen();
        long channelHandle = NativeBindings.createDataChannel(
                handle,
                label,
                options.ordered(),
                optionalUnsigned16(options.maxPacketLifeTime()),
                optionalUnsigned16(options.maxRetransmits()),
                options.protocol(),
                optionalUnsigned16(options.negotiatedId()),
                operationTimeoutMillis);
        DataChannel channel =
                new DataChannel(
                        channelHandle,
                        label,
                        options.protocol(),
                        options.ordered(),
                        callbacks,
                        false);
        dataChannels.put(channelHandle, channel);
        return channel;
    }

    /** Closes the peer and all owned DataChannels. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        NativeEventDispatcher.unregister(handle);
        for (DataChannel channel : dataChannels.values()) {
            channel.markClosed();
        }
        dataChannels.clear();
        try {
            NativeBindings.closePeer(handle, operationTimeoutMillis);
        } finally {
            state = PeerConnectionState.CLOSED;
        }
    }

    void closeForShutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        NativeEventDispatcher.unregister(handle);
        for (DataChannel channel : dataChannels.values()) {
            channel.markClosed();
        }
        dataChannels.clear();
        state = PeerConnectionState.CLOSED;
    }

    void handleNativeEvent(NativeEvent event) {
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
            case NativeBindings.EVENT_DATA_CHANNEL -> {
                boolean ordered = (event.number() & 1) != 0;
                boolean initiallyOpen = (event.number() & 2) != 0;
                DataChannel channel = new DataChannel(
                        event.channelHandle(),
                        event.text() == null ? "" : event.text(),
                        event.secondaryText() == null ? "" : event.secondaryText(),
                        ordered,
                        callbacks,
                        initiallyOpen);
                dataChannels.put(event.channelHandle(), channel);
                callbacks.execute(() -> {
                    onDataChannel.accept(channel);
                    if (initiallyOpen) {
                        channel.notifyOpen();
                    }
                });
            }
            case NativeBindings.EVENT_DATA_CHANNEL_OPEN,
                    NativeBindings.EVENT_DATA_CHANNEL_CLOSING,
                    NativeBindings.EVENT_DATA_CHANNEL_CLOSED,
                    NativeBindings.EVENT_DATA_CHANNEL_ERROR,
                    NativeBindings.EVENT_DATA_CHANNEL_TEXT,
                    NativeBindings.EVENT_DATA_CHANNEL_BINARY -> {
                DataChannel channel = dataChannels.get(event.channelHandle());
                if (channel != null) {
                    channel.handleNativeEvent(event);
                    if (event.kind() == NativeBindings.EVENT_DATA_CHANNEL_CLOSED) {
                        dataChannels.remove(event.channelHandle(), channel);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported peer event: " + event.kind());
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PeerConnection is closed");
        }
    }

    private static int optionalUnsigned16(Integer value) {
        return value == null ? -1 : value;
    }

    private static <T> T enumValue(T[] values, int ordinal, String name) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown " + name + ": " + ordinal);
        }
        return values[ordinal];
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
