package com.enderdash.kestara.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class KestaraWebRtcTest {
    @Test
    void loadsCompatibleNativeLibrary() {
        assertEquals(KestaraWebRtc.NATIVE_ABI_VERSION, KestaraWebRtc.nativeAbiVersion());
        assertFalse(KestaraWebRtc.nativeLibraryVersion().isBlank());
    }
}
