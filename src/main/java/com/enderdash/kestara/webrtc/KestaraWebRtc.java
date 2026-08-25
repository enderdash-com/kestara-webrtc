package com.enderdash.kestara.webrtc;

import com.enderdash.kestara.webrtc.internal.NativeBindings;

/** Provides native library metadata for Kestara WebRTC. */
public final class KestaraWebRtc {
    /** The native ABI required by this Java API. */
    public static final int NATIVE_ABI_VERSION = 3;

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

    static void ensureNativeAbi() {
        // Calling this method initializes the class and performs the ABI check.
    }
}
