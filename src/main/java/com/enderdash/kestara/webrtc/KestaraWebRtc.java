package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBindings;

/** Provides metadata and compatibility entry points for Kestara WebRTC. */
public final class KestaraWebRtc {
    /** The native ABI required by this Java API. */
    public static final int NATIVE_ABI_VERSION = 3;

    private static final Object DEFAULT_RUNTIME_LOCK = new Object();
    private static WebRtcRuntime defaultRuntime;

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

    /** Closes the shared compatibility runtime used by {@link PeerConnection#create}. */
    public static void shutdown() {
        WebRtcRuntime runtime;
        synchronized (DEFAULT_RUNTIME_LOCK) {
            runtime = defaultRuntime;
            defaultRuntime = null;
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    static void ensureNativeCompatibility() {
        // Calling this method initializes the class and performs the ABI check.
    }

    static WebRtcRuntime defaultRuntime() {
        synchronized (DEFAULT_RUNTIME_LOCK) {
            if (defaultRuntime == null || defaultRuntime.diagnostics().closed()) {
                defaultRuntime = WebRtcRuntime.create();
            }
            return defaultRuntime;
        }
    }
}
