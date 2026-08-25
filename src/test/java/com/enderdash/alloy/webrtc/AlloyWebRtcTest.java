package com.enderdash.alloy.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class AlloyWebRtcTest {
    @Test
    void loadsCompatibleNativeLibrary() {
        assertEquals(AlloyWebRtc.NATIVE_ABI_VERSION, AlloyWebRtc.nativeAbiVersion());
        assertFalse(AlloyWebRtc.nativeLibraryVersion().isBlank());
    }
}
