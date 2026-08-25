package com.enderdash.kestara.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PeerConnectionIntegrationTest {
    @Test
    void createsAndClosesPeerConnection() {
        try (WebRtcRuntime runtime = WebRtcRuntime.create();
                PeerConnection peer =
                        runtime.createPeerConnection(PeerConnectionConfiguration.DEFAULT)) {
            assertNotNull(peer);
        }
    }

    @Test
    void runtimeCloseClosesRemainingPeersAndAllowsANewRuntime() {
        PeerConnection first;
        try (WebRtcRuntime runtime = WebRtcRuntime.create()) {
            first = runtime.createPeerConnection(PeerConnectionConfiguration.DEFAULT);
        }

        assertEquals(PeerConnectionState.CLOSED, first.state());
        try (WebRtcRuntime runtime = WebRtcRuntime.create();
                PeerConnection second =
                        runtime.createPeerConnection(PeerConnectionConfiguration.DEFAULT)) {
            assertNotNull(second);
        }
    }

    @Test
    void keepsExplicitRuntimesIsolated() throws Exception {
        WebRtcRuntimeOptions firstOptions =
                WebRtcRuntimeOptions.DEFAULT.withWorkerThreads(1);
        WebRtcRuntimeOptions secondOptions =
                WebRtcRuntimeOptions.DEFAULT.withWorkerThreads(3);

        try (WebRtcRuntime first = WebRtcRuntime.create(firstOptions);
                WebRtcRuntime second = WebRtcRuntime.create(secondOptions)) {
            PeerConnection firstPeer = first.createPeerConnectionAsync(
                            PeerConnectionConfiguration.DEFAULT)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            PeerConnection secondPeer = second.createPeerConnectionAsync(
                            PeerConnectionConfiguration.DEFAULT)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(1, first.diagnostics().workerThreads());
            assertEquals(3, second.diagnostics().workerThreads());
            assertEquals(1, first.diagnostics().peerConnections());
            assertEquals(1, second.diagnostics().peerConnections());

            first.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(first.diagnostics().closed());
            assertEquals(PeerConnectionState.CLOSED, firstPeer.state());
            assertFalse(second.diagnostics().closed());
            assertEquals(1, second.diagnostics().peerConnections());
            secondPeer.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void ownsOneDtlsCertificateForEachRuntime() {
        String firstFingerprint;
        try (WebRtcRuntime runtime = WebRtcRuntime.create();
                PeerConnection first = runtime.createPeerConnection(PeerConnectionConfiguration.DEFAULT);
                PeerConnection second = runtime.createPeerConnection(PeerConnectionConfiguration.DEFAULT)) {
            firstFingerprint = runtime.certificateFingerprint();
            assertTrue(firstFingerprint.matches("(?:[0-9a-f]{2}:){31}[0-9a-f]{2}"));
            assertTrue(first.createOffer()
                    .sdp()
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("a=fingerprint:sha-256 " + firstFingerprint));
            assertTrue(second.createOffer()
                    .sdp()
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("a=fingerprint:sha-256 " + firstFingerprint));
        }

        try (WebRtcRuntime runtime = WebRtcRuntime.create()) {
            assertNotEquals(firstFingerprint, runtime.certificateFingerprint());
        }
    }

    @Test
    void retriesActualTransportBindingsAcrossThePortRange() throws Exception {
        try (DatagramSocket reservation = reserveUdpPort()) {
            int reservedPort = reservation.getLocalPort();

            try (WebRtcRuntime runtime = WebRtcRuntime.create()) {
                PeerConnectionConfiguration unavailable = PeerConnectionConfiguration.DEFAULT
                        .withIceOptions(IceOptions.DEFAULT.withMdns(
                                IceMdnsMode.DISABLED, Duration.ofSeconds(1)))
                        .withPortRange(reservedPort, reservedPort);
                assertThrows(WebRtcException.class, () -> runtime.createPeerConnection(unavailable));

                int maximum = reservedPort + 20;
                PeerConnectionConfiguration retrying = unavailable.withPortRange(reservedPort, maximum);
                try (PeerConnection peer = runtime.createPeerConnection(retrying)) {
                    assertNotNull(peer);
                }
            }
        }
    }

    private static DatagramSocket reserveUdpPort() throws SocketException {
        for (int port = 40_000; port <= 45_000; port++) {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(false);
            try {
                socket.bind(new InetSocketAddress("0.0.0.0", port));
                return socket;
            } catch (SocketException error) {
                socket.close();
            }
        }
        throw new SocketException("No UDP test port is available");
    }

    @Test
    void appliesAdvancedOptionsAndSupportsIceRecoveryOperations() throws Exception {
        IceOptions ice = IceOptions.DEFAULT
                .withTimeouts(Duration.ofSeconds(6), Duration.ofSeconds(20), Duration.ofSeconds(2))
                .withConnectionAttempts(Duration.ofMillis(100), 8)
                .withAcceptanceWaits(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO)
                .withNetworkTypes(Set.of(IceNetworkType.UDP4))
                .withMdns(IceMdnsMode.DISABLED, Duration.ofSeconds(1))
                .withCandidatePoolSize(1);
        SctpOptions sctp = new SctpOptions(8 * 1024 * 1024, 512 * 1024, 128 * 1024);
        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withIceOptions(ice)
                .withSctpOptions(sctp);

        try (WebRtcRuntime runtime = WebRtcRuntime.create();
                PeerConnection peer = runtime.createPeerConnection(configuration)) {
            CountDownLatch candidate = new CountDownLatch(1);
            peer.onLocalCandidate(ignored -> candidate.countDown());
            peer.createDataChannel("candidate-pool");
            peer.setLocalDescription(SessionDescriptionType.OFFER);

            assertTrue(candidate.await(5, TimeUnit.SECONDS), "The candidate pool produced no candidate");
            peer.setConfigurationAsync(configuration.withIceTransportPolicy(IceTransportPolicy.ALL))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            peer.restartIceAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void exchangesBinaryDataOverAnOrderedChannel() throws Exception {
        ExecutorService callbacks = Executors.newCachedThreadPool();
        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withCallbackExecutor(callbacks)
                .withOperationTimeout(Duration.ofSeconds(5));
        try (WebRtcRuntime runtime = WebRtcRuntime.create();
                PeerConnection offerer = runtime.createPeerConnection(configuration);
                PeerConnection answerer = runtime.createPeerConnection(configuration)) {
            CandidateRelay offererCandidates = new CandidateRelay(answerer);
            CandidateRelay answererCandidates = new CandidateRelay(offerer);
            offerer.onLocalCandidate(offererCandidates::accept);
            answerer.onLocalCandidate(answererCandidates::accept);

            CountDownLatch offererOpen = new CountDownLatch(1);
            CountDownLatch incomingChannel = new CountDownLatch(1);
            CountDownLatch answererOpen = new CountDownLatch(1);
            CountDownLatch binaryReceived = new CountDownLatch(1);
            AtomicReference<byte[]> received = new AtomicReference<>();

            answerer.onDataChannel(channel -> {
                incomingChannel.countDown();
                channel.onOpen(answererOpen::countDown);
                channel.onMessage(new DataChannelMessageHandler() {
                    @Override
                    public void onBinary(ByteBuffer data) {
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        received.set(bytes);
                        binaryReceived.countDown();
                    }
                });
            });

            DataChannel channel = offerer.createDataChannel("kestara-test");
            channel.onOpen(offererOpen::countDown);

            SessionDescription offer = offerer.createOfferAsync()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            offerer.setLocalDescriptionAsync(offer)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            answerer.setRemoteDescriptionAsync(offer)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            offererCandidates.remoteDescriptionReady();

            SessionDescription answer = answerer.createAnswer();
            answerer.setLocalDescription(answer);
            offerer.setRemoteDescription(answer);
            answererCandidates.remoteDescriptionReady();

            assertTrue(offererOpen.await(10, TimeUnit.SECONDS), "Offerer DataChannel did not open");
            assertTrue(incomingChannel.await(10, TimeUnit.SECONDS), "Answerer did not receive a DataChannel");
            assertTrue(answererOpen.await(10, TimeUnit.SECONDS), "Answerer DataChannel did not open");

            byte[] payload = {1, 3, 3, 7};
            ByteBuffer source = ByteBuffer.wrap(payload);
            channel.sendAsync(source).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(0, source.position());

            assertTrue(binaryReceived.await(10, TimeUnit.SECONDS), "Binary message was not received");
            assertArrayEquals(payload, received.get());
        } finally {
            callbacks.shutdownNow();
        }
    }

    private static final class CandidateRelay {
        private final PeerConnection remote;
        private final List<IceCandidate> pending = new CopyOnWriteArrayList<>();
        private final AtomicBoolean ready = new AtomicBoolean();

        private CandidateRelay(PeerConnection remote) {
            this.remote = remote;
        }

        private void accept(IceCandidate candidate) {
            if (ready.get()) {
                remote.addIceCandidateAsync(candidate).toCompletableFuture().join();
            } else {
                pending.add(candidate);
                if (ready.get() && pending.remove(candidate)) {
                    remote.addIceCandidateAsync(candidate).toCompletableFuture().join();
                }
            }
        }

        private void remoteDescriptionReady() {
            ready.set(true);
            for (IceCandidate candidate : pending) {
                if (pending.remove(candidate)) {
                    remote.addIceCandidateAsync(candidate).toCompletableFuture().join();
                }
            }
        }
    }
}
