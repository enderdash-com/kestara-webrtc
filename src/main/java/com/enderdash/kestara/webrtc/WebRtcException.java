package com.enderdash.kestara.webrtc;

/** Reports a WebRTC operation error from the native runtime. */
public final class WebRtcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a native error message.
     *
     * @param message the error message
     */
    public WebRtcException(String message) {
        super(message);
    }

    /**
     * Creates an exception for a Java-side runtime failure.
     *
     * @param message the error message
     * @param cause the underlying failure
     */
    public WebRtcException(String message, Throwable cause) {
        super(message, cause);
    }
}
