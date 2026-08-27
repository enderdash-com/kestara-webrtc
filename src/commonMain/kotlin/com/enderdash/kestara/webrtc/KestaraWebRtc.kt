package com.enderdash.kestara.webrtc

import com.enderdash.kestara.webrtc.internal.platformNativeBridge

public object KestaraWebRtc {
  public const val REQUIRED_NATIVE_ABI: Int = 7

  public val nativeAbiVersion: Int
    get() = platformNativeBridge().abiVersion

  public val nativeLibraryVersion: String
    get() = platformNativeBridge().libraryVersion

  internal fun ensureNativeAbi() {
    val actual = nativeAbiVersion
    check(actual == REQUIRED_NATIVE_ABI) {
      "Kestara WebRTC native ABI mismatch: expected $REQUIRED_NATIVE_ABI but loaded $actual"
    }
  }
}
