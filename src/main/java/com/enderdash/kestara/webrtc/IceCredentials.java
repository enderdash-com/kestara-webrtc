package com.enderdash.kestara.webrtc;

import java.util.Objects;

/** Fixed local ICE credentials for advanced signaling integrations. */
public record IceCredentials(String usernameFragment, String password) {
    /**
     * Validates the ICE credentials.
     *
     * @param usernameFragment the local username fragment
     * @param password the local password
     */
    public IceCredentials {
        Objects.requireNonNull(usernameFragment, "usernameFragment");
        Objects.requireNonNull(password, "password");
        if (usernameFragment.length() < 4 || usernameFragment.length() > 256) {
            throw new IllegalArgumentException("ICE username fragment must contain 4 to 256 characters");
        }
        if (password.length() < 22 || password.length() > 256) {
            throw new IllegalArgumentException("ICE password must contain 22 to 256 characters");
        }
    }
}
