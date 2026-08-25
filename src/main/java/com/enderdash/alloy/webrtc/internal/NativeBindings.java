package com.enderdash.alloy.webrtc.internal;

import java.util.Objects;

/** JNI methods implemented by the Alloy WebRTC native library. */
public final class NativeBindings {
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

    private static native int nativeAbiVersion();

    private static native String nativeLibraryVersion();
}
