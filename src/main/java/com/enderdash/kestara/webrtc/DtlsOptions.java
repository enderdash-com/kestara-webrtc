package com.enderdash.kestara.webrtc;

import java.util.List;
import java.util.Objects;

/** Advanced DTLS negotiation and replay-protection options. */
public record DtlsOptions(
        DtlsRole answeringRole,
        boolean mediaLevelFingerprints,
        int replayProtectionWindow,
        List<DtlsCipherSuite> cipherSuites) {
    /** Secure defaults for the generated ECDSA certificate. */
    public static final DtlsOptions DEFAULT = new DtlsOptions(
            DtlsRole.AUTO,
            false,
            64,
            List.of(
                    DtlsCipherSuite.ECDHE_ECDSA_AES_128_GCM_SHA256,
                    DtlsCipherSuite.ECDHE_ECDSA_CHACHA20_POLY1305_SHA256,
                    DtlsCipherSuite.ECDHE_ECDSA_AES_256_CBC_SHA));

    /**
     * Validates and copies the DTLS options.
     *
     * @param answeringRole the role used for answers
     * @param mediaLevelFingerprints whether SDP fingerprints are media-level
     * @param replayProtectionWindow the anti-replay window size
     * @param cipherSuites the ordered cipher preference
     */
    public DtlsOptions {
        Objects.requireNonNull(answeringRole, "answeringRole");
        cipherSuites = List.copyOf(Objects.requireNonNull(cipherSuites, "cipherSuites"));
        if (replayProtectionWindow < 1) {
            throw new IllegalArgumentException("DTLS replay protection window must be positive");
        }
        if (cipherSuites.isEmpty()) {
            throw new IllegalArgumentException("At least one DTLS cipher suite is required");
        }
    }

    /** Returns a copy with the specified answering role.
     * @param value the role
     * @return the updated options
     */
    public DtlsOptions withAnsweringRole(DtlsRole value) {
        return new DtlsOptions(value, mediaLevelFingerprints, replayProtectionWindow, cipherSuites);
    }

    /** Returns a copy with session-level or media-level SDP fingerprints.
     * @param value {@code true} for media-level fingerprints
     * @return the updated options
     */
    public DtlsOptions withMediaLevelFingerprints(boolean value) {
        return new DtlsOptions(answeringRole, value, replayProtectionWindow, cipherSuites);
    }

    /** Returns a copy with the specified anti-replay window.
     * @param value the window size
     * @return the updated options
     */
    public DtlsOptions withReplayProtectionWindow(int value) {
        return new DtlsOptions(answeringRole, mediaLevelFingerprints, value, cipherSuites);
    }

    /** Returns a copy with the ordered cipher preference.
     * @param value the cipher suites
     * @return the updated options
     */
    public DtlsOptions withCipherSuites(List<DtlsCipherSuite> value) {
        return new DtlsOptions(answeringRole, mediaLevelFingerprints, replayProtectionWindow, value);
    }
}
