package com.enderdash.kestara.webrtc.internal;

import java.util.Objects;

/**
 * JNI methods implemented by the Kestara WebRTC native library.
 *
 * @hidden
 */
public final class NativeBindings {
    public static final int EVENT_LOCAL_CANDIDATE = 1;
    public static final int EVENT_PEER_STATE = 2;
    public static final int EVENT_ICE_CONNECTION_STATE = 3;
    public static final int EVENT_ICE_GATHERING_STATE = 4;
    public static final int EVENT_DATA_CHANNEL = 5;
    public static final int EVENT_DATA_CHANNEL_OPEN = 6;
    public static final int EVENT_DATA_CHANNEL_CLOSING = 7;
    public static final int EVENT_DATA_CHANNEL_CLOSED = 8;
    public static final int EVENT_DATA_CHANNEL_ERROR = 9;
    public static final int EVENT_DATA_CHANNEL_TEXT = 10;
    public static final int EVENT_DATA_CHANNEL_BINARY = 11;
    public static final int EVENT_OPERATION_COMPLETE = 12;
    public static final int EVENT_DATA_CHANNEL_BUFFERED_AMOUNT_LOW = 13;
    public static final int EVENT_DATA_CHANNEL_BUFFERED_AMOUNT_HIGH = 14;

    static {
        NativeLibraryLoader.load();
    }

    private NativeBindings() {}

    public static int abiVersion() {
        return nativeAbiVersion();
    }

    public static String libraryVersion() {
        return Objects.requireNonNull(
                nativeLibraryVersion(), "The native library returned a null version");
    }

    public static native long nativeCreateRuntime(
            int workerThreads,
            int reactorThreads,
            String certificatePem,
            String[] sharedUdpAddresses,
            String[] sharedTcpAddresses,
            int sharedMinPort,
            int sharedMaxPort);

    public static native String nativeRuntimeCertificateFingerprint(long runtime);

    public static native String nativeRuntimeCertificatePem(long runtime);

    public static native void nativeSubmitCreatePeer(
            long runtime,
            long operation,
            String[] urls,
            String[] usernames,
            String[] credentials,
            int minPort,
            int maxPort,
            int iceTransportPolicy,
            long disconnectedTimeoutMillis,
            long failedTimeoutMillis,
            long keepAliveIntervalMillis,
            long checkIntervalMillis,
            int maxBindingRequests,
            long hostAcceptanceMinWaitMillis,
            long serverReflexiveAcceptanceMinWaitMillis,
            long peerReflexiveAcceptanceMinWaitMillis,
            long relayAcceptanceMinWaitMillis,
            int networkTypeMask,
            int mdnsMode,
            long mdnsQueryTimeoutMillis,
            boolean iceLite,
            String[] natAddresses,
            int natMappingType,
            boolean discardLocalCandidatesOnRestart,
            int candidatePoolSize,
            boolean includeLoopbackCandidate,
            String mdnsLocalName,
            String mdnsLocalAddress,
            String iceUsernameFragment,
            String icePassword,
            int sctpSendBufferLimit,
            int sctpReceiveBufferSize,
            int sctpMaximumMessageSize,
            int dtlsAnsweringRole,
            boolean mediaLevelFingerprints,
            int dtlsReplayProtectionWindow,
            int dtlsCipherSuiteMask,
            String[] udpBindAddresses,
            String[] tcpBindAddresses,
            int receiveMtu,
            long timeoutMillis);

    public static native void nativeSubmitRestartIce(
            long runtime, long operation, long peer, long timeoutMillis);

    public static native void nativeSubmitSetConfiguration(
            long runtime,
            long operation,
            long peer,
            String[] urls,
            String[] usernames,
            String[] credentials,
            int iceTransportPolicy,
            long timeoutMillis);

    public static native void nativeSubmitCreateDescription(
            long runtime, long operation, long peer, int type, long timeoutMillis);

    public static native void nativeSubmitSetLocalDescription(
            long runtime,
            long operation,
            long peer,
            String sdp,
            int type,
            long timeoutMillis);

    public static native void nativeSubmitSetRemoteDescription(
            long runtime,
            long operation,
            long peer,
            String sdp,
            int type,
            long timeoutMillis);

    public static native void nativeSubmitAddIceCandidate(
            long runtime,
            long operation,
            long peer,
            String candidate,
            String sdpMid,
            int sdpMLineIndex,
            long timeoutMillis);

    public static native void nativeSubmitCreateDataChannel(
            long runtime,
            long operation,
            long peer,
            String label,
            boolean ordered,
            int maxPacketLifeTime,
            int maxRetransmits,
            String protocol,
            int negotiatedId,
            long timeoutMillis);

    public static native void nativeSubmitSendDataChannelText(
            long runtime, long operation, long channel, String text, long timeoutMillis);

    public static native void nativeSubmitSendDataChannelBinary(
            long runtime, long operation, long channel, byte[] data, long timeoutMillis);

    public static native void nativeSubmitTrySendDataChannelText(
            long runtime, long operation, long channel, String text, long timeoutMillis);

    public static native void nativeSubmitTrySendDataChannelBinary(
            long runtime, long operation, long channel, byte[] data, long timeoutMillis);

    public static native void nativeSubmitDataChannelWritable(
            long runtime, long operation, long channel, long timeoutMillis);

    public static native void nativeSubmitDataChannelOutstandingBytes(
            long runtime, long operation, long channel, long timeoutMillis);

    public static native void nativeSubmitSetDataChannelThresholds(
            long runtime, long operation, long channel, long low, long high, long timeoutMillis);

    public static native void nativeSubmitGetStats(
            long runtime, long operation, long peer, long timeoutMillis);

    public static native void nativeSubmitRotateCertificate(
            long runtime, long operation, String certificatePem, long timeoutMillis);

    public static native void nativeSubmitCloseDataChannel(
            long runtime, long operation, long channel, long timeoutMillis);

    public static native void nativeSubmitClosePeer(
            long runtime, long operation, long peer, long timeoutMillis);

    public static native void nativeSubmitCloseRuntime(
            long runtime, long operation, long timeoutMillis);

    public static native NativeEvent nativePollRuntimeEvent(long runtime, long timeoutMillis);

    public static native void nativeWakeRuntime(long runtime);

    public static native void nativeReleaseRuntime(long runtime);

    private static native int nativeAbiVersion();

    private static native String nativeLibraryVersion();
}
