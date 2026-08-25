package com.enderdash.kestara.webrtc;

/** Certificate-based DTLS cipher suites supported by the native backend. */
public enum DtlsCipherSuite {
    /** ECDSA with AES-128 CCM. */
    ECDHE_ECDSA_AES_128_CCM,
    /** ECDSA with AES-128 CCM-8. */
    ECDHE_ECDSA_AES_128_CCM_8,
    /** ECDSA with AES-128 GCM. */
    ECDHE_ECDSA_AES_128_GCM_SHA256,
    /** RSA with AES-128 GCM. */
    ECDHE_RSA_AES_128_GCM_SHA256,
    /** ECDSA with AES-256 CBC. */
    ECDHE_ECDSA_AES_256_CBC_SHA,
    /** RSA with AES-256 CBC. */
    ECDHE_RSA_AES_256_CBC_SHA,
    /** RSA with ChaCha20-Poly1305. */
    ECDHE_RSA_CHACHA20_POLY1305_SHA256,
    /** ECDSA with ChaCha20-Poly1305. */
    ECDHE_ECDSA_CHACHA20_POLY1305_SHA256
}
