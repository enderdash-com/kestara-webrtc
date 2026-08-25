package com.enderdash.alloy.webrtc;

import java.nio.ByteBuffer;

/** Receives text and binary DataChannel messages. */
public interface DataChannelMessageHandler {
    /**
     * Receives a UTF-8 text message.
     *
     * @param text the received text
     */
    default void onText(String text) {}

    /**
     * Receives a read-only binary message.
     *
     * @param data the received data; valid after this method returns
     */
    default void onBinary(ByteBuffer data) {}
}
