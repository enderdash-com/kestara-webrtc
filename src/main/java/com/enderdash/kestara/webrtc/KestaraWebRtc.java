package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBindings;

/** Provides metadata and entry points for Kestara WebRTC. */
public final class KestaraWebRtc {
    /** The native ABI required by this Java API. */
    public static final int NATIVE_ABI_VERSION = 2;

    static {
        int actualVersion = NativeBindings.abiVersion();
        if (actualVersion != NATIVE_ABI_VERSION) {
            throw new LinkageError(
                    "Kestara WebRTC native ABI mismatch: expected "
                            + NATIVE_ABI_VERSION
                            + ", found "
                            + actualVersion);
        }
    }

    private KestaraWebRtc() {}

    /**
     * Returns the ABI version of the loaded native library.
     *
     * @return the native ABI version
     */
    public static int nativeAbiVersion() {
        return NativeBindings.abiVersion();
    }

    /**
     * Returns the version of the loaded native library.
     *
     * @return the native library version
     */
    public static String nativeLibraryVersion() {
        return NativeBindings.libraryVersion();
    }

    /** Closes all peers, stops event dispatch, and releases the native runtime. */
    public static void shutdown() {
        NativeEventDispatcher.closeAll();
        RuntimeException failure = null;
        try {
            NativeEventDispatcher.stop();
        } catch (RuntimeException error) {
            failure = addFailure(failure, error);
        }
        try {
            NativeBindings.shutdown();
        } catch (RuntimeException error) {
            failure = addFailure(failure, error);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException addFailure(
            RuntimeException failure, RuntimeException additionalFailure) {
        if (failure == null) {
            return additionalFailure;
        }
        failure.addSuppressed(additionalFailure);
        return failure;
    }
}
