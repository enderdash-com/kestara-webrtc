package com.enderdash.alloy.webrtc;

import com.enderdash.alloy.webrtc.internal.NativeBindings;

/** Provides metadata and entry points for Alloy WebRTC. */
public final class AlloyWebRtc {
    /** The native ABI required by this Java API. */
    public static final int NATIVE_ABI_VERSION = 1;

    static {
        int actualVersion = NativeBindings.abiVersion();
        if (actualVersion != NATIVE_ABI_VERSION) {
            throw new LinkageError(
                    "Alloy WebRTC native ABI mismatch: expected "
                            + NATIVE_ABI_VERSION
                            + ", found "
                            + actualVersion);
        }
    }

    private AlloyWebRtc() {}

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
}
