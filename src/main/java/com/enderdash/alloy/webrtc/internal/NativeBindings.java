package com.enderdash.alloy.webrtc.internal;

import java.util.Objects;

/**
 * JNI methods implemented by the Alloy WebRTC native library.
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

    static {
        NativeLibraryLoader.load();
    }

    private NativeBindings() {}

    /**
     * Returns the native ABI version.
     *
     * @return the native ABI version
     */
    public static int abiVersion() {
        return nativeAbiVersion();
    }

    /**
     * Returns the native library version.
     *
     * @return the native library version
     */
    public static String libraryVersion() {
        return Objects.requireNonNull(
                nativeLibraryVersion(), "The native library returned a null version");
    }

    public static long createPeer(
            String[] urls,
            String[] usernames,
            String[] credentials,
            int minPort,
            int maxPort,
            int iceTransportPolicy,
            int dataChannelSendBufferLimit,
            long operationTimeoutMillis) {
        return nativeCreatePeer(
                urls,
                usernames,
                credentials,
                minPort,
                maxPort,
                iceTransportPolicy,
                dataChannelSendBufferLimit,
                operationTimeoutMillis);
    }

    public static String createDescription(long peer, int type) {
        return nativeCreateDescription(peer, type);
    }

    public static void setLocalDescription(long peer, String sdp, int type, long timeoutMillis) {
        nativeSetLocalDescription(peer, sdp, type, timeoutMillis);
    }

    public static void setRemoteDescription(long peer, String sdp, int type, long timeoutMillis) {
        nativeSetRemoteDescription(peer, sdp, type, timeoutMillis);
    }

    public static void addIceCandidate(
            long peer, String candidate, String sdpMid, int sdpMLineIndex, long timeoutMillis) {
        nativeAddIceCandidate(peer, candidate, sdpMid, sdpMLineIndex, timeoutMillis);
    }

    public static long createDataChannel(
            long peer,
            String label,
            boolean ordered,
            int maxPacketLifeTime,
            int maxRetransmits,
            String protocol,
            int negotiatedId,
            long timeoutMillis) {
        return nativeCreateDataChannel(
                peer,
                label,
                ordered,
                maxPacketLifeTime,
                maxRetransmits,
                protocol,
                negotiatedId,
                timeoutMillis);
    }

    public static void sendDataChannelText(long channel, String text) {
        nativeSendDataChannelText(channel, text);
    }

    public static void sendDataChannelBinary(long channel, byte[] data) {
        nativeSendDataChannelBinary(channel, data);
    }

    public static void closeDataChannel(long channel) {
        nativeCloseDataChannel(channel);
    }

    public static void closePeer(long peer, long timeoutMillis) {
        nativeClosePeer(peer, timeoutMillis);
    }

    public static NativeEvent pollEvent(long timeoutMillis) {
        return nativePollEvent(timeoutMillis);
    }

    public static void wakeEventLoop() {
        nativeWakeEventLoop();
    }

    public static void shutdown() {
        nativeShutdown();
    }

    private static native int nativeAbiVersion();

    private static native String nativeLibraryVersion();

    private static native long nativeCreatePeer(
            String[] urls,
            String[] usernames,
            String[] credentials,
            int minPort,
            int maxPort,
            int iceTransportPolicy,
            int dataChannelSendBufferLimit,
            long operationTimeoutMillis);

    private static native String nativeCreateDescription(long peer, int type);

    private static native void nativeSetLocalDescription(
            long peer, String sdp, int type, long timeoutMillis);

    private static native void nativeSetRemoteDescription(
            long peer, String sdp, int type, long timeoutMillis);

    private static native void nativeAddIceCandidate(
            long peer, String candidate, String sdpMid, int sdpMLineIndex, long timeoutMillis);

    private static native long nativeCreateDataChannel(
            long peer,
            String label,
            boolean ordered,
            int maxPacketLifeTime,
            int maxRetransmits,
            String protocol,
            int negotiatedId,
            long timeoutMillis);

    private static native void nativeSendDataChannelText(long channel, String text);

    private static native void nativeSendDataChannelBinary(long channel, byte[] data);

    private static native void nativeCloseDataChannel(long channel);

    private static native void nativeClosePeer(long peer, long timeoutMillis);

    private static native NativeEvent nativePollEvent(long timeoutMillis);

    private static native void nativeWakeEventLoop();

    private static native void nativeShutdown();
}
