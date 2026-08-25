package com.enderdash.kestara.webrtc.internal;

import java.nio.ByteBuffer;

/** A native allocation and its direct Java view. @hidden */
public record NativeBufferDescriptor(long handle, ByteBuffer buffer) {}
