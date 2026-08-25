package com.enderdash.alloy.webrtc;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Configures one STUN or TURN server with one or more URLs.
 *
 * @param urls the STUN or TURN URLs
 * @param username the TURN username, or an empty string
 * @param credential the TURN credential, or an empty string
 */
public record IceServer(List<String> urls, String username, String credential) {
    /**
     * Validates and copies this ICE server configuration.
     *
     * @param urls the STUN or TURN URLs
     * @param username the TURN username, or {@code null}
     * @param credential the TURN credential, or {@code null}
     */
    public IceServer {
        urls = List.copyOf(Objects.requireNonNull(urls, "urls"));
        if (urls.isEmpty() || urls.stream().anyMatch(url -> url == null || url.isBlank())) {
            throw new IllegalArgumentException("urls must contain at least one non-blank URL");
        }
        username = username == null ? "" : username;
        credential = credential == null ? "" : credential;
    }

    /**
     * Creates an ICE server without credentials.
     *
     * @param urls the STUN or TURN URLs
     * @return the ICE server
     */
    public static IceServer of(String... urls) {
        return new IceServer(Arrays.asList(urls), "", "");
    }

    /**
     * Creates an ICE server with TURN credentials.
     *
     * @param username the TURN username
     * @param credential the TURN credential
     * @param urls the TURN URLs
     * @return the ICE server
     */
    public static IceServer authenticated(String username, String credential, String... urls) {
        return new IceServer(Arrays.asList(urls), username, credential);
    }
}
