package com.enderdash.kestara.webrtc.internal;

import java.nio.ByteBuffer;

/**
 * One event copied from the Rust runtime.
 *
 * @hidden
 */
public record NativeEvent(
        int kind,
        long peerHandle,
        long channelHandle,
        long operationHandle,
        String text,
        String secondaryText,
        int number,
        byte[] data,
        long messageHandle,
        ByteBuffer directData) {}
