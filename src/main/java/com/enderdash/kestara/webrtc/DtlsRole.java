package com.enderdash.kestara.webrtc;

/** DTLS role used when answering an offer. */
public enum DtlsRole {
    /** Let the backend select the role. */
    AUTO,
    /** Act as the DTLS client. */
    CLIENT,
    /** Act as the DTLS server. */
    SERVER
}
