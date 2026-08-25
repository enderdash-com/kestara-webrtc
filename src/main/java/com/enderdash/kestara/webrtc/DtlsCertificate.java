package com.enderdash.kestara.webrtc;

import java.util.Objects;

/** A DTLS certificate and private key encoded in the Kestara PEM format. */
public final class DtlsCertificate {
    private final String pem;

    private DtlsCertificate(String pem) {
        this.pem = pem;
    }

    /**
     * Imports a certificate, its private key, and its expiry metadata.
     *
     * @param pem the complete PEM document
     * @return the certificate
     */
    public static DtlsCertificate fromPem(String pem) {
        Objects.requireNonNull(pem, "pem");
        if (pem.isBlank()) {
            throw new IllegalArgumentException("Certificate PEM must not be blank");
        }
        return new DtlsCertificate(pem);
    }

    /**
     * Returns the sensitive PEM document, including the private key.
     *
     * @return the PEM document
     */
    public String pem() {
        return pem;
    }

    @Override
    public String toString() {
        return "DtlsCertificate[redacted]";
    }
}
