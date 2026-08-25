package com.enderdash.kestara.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    void exportsImportsAndRotatesRuntimeCertificates() throws Exception {
        DtlsCertificate original;
        String originalFingerprint;
        try (WebRtcRuntime runtime = WebRtcRuntime.create()) {
            original = runtime.certificate();
            originalFingerprint = runtime.certificateFingerprint();
            String rotated = runtime.rotateCertificateAsync()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertNotEquals(originalFingerprint, rotated);
        }

        WebRtcRuntimeOptions options = WebRtcRuntimeOptions.DEFAULT.withCertificate(original);
        try (WebRtcRuntime runtime = WebRtcRuntime.create(options)) {
            assertEquals(originalFingerprint, runtime.certificateFingerprint());
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
                .withIncludeLoopbackCandidate(true)
                .withCredentials(new IceCredentials(
                        "kestara-test", "kestara-test-password-123"))
                .withCandidatePoolSize(1);
        SctpOptions sctp = new SctpOptions(8 * 1024 * 1024, 512 * 1024, 128 * 1024, 16);
        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withIceOptions(ice)
                .withSctpOptions(sctp)
                .withDtlsOptions(DtlsOptions.DEFAULT
                        .withAnsweringRole(DtlsRole.CLIENT)
                        .withMediaLevelFingerprints(true)
                        .withReplayProtectionWindow(128))
                .withTransportOptions(new TransportOptions(
                        List.of("127.0.0.1"), List.of(), 1_200));

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
                .withSctpOptions(SctpOptions.DEFAULT.withReceiveQueueCapacity(2))
                .withOperationTimeout(Duration.ofSeconds(5));
        WebRtcRuntimeOptions runtimeOptions = WebRtcRuntimeOptions.DEFAULT
                .withReactorThreads(2)
                .withSharedSockets(new SharedSocketOptions(
                        List.of("0.0.0.0"), List.of("0.0.0.0"), 0, 0));
        try (WebRtcRuntime runtime = WebRtcRuntime.create(runtimeOptions);
                WebRtcRuntime remoteRuntime = WebRtcRuntime.create();
                PeerConnection offerer = runtime.createPeerConnection(configuration);
                PeerConnection idleSharedPeer = runtime.createPeerConnection(configuration);
                PeerConnection answerer = remoteRuntime.createPeerConnection(configuration)) {
            assertNotNull(idleSharedPeer);
            CandidateRelay offererCandidates = new CandidateRelay(answerer);
            CandidateRelay answererCandidates = new CandidateRelay(offerer);
            offerer.onLocalCandidate(offererCandidates::accept);
            answerer.onLocalCandidate(answererCandidates::accept);

            CountDownLatch offererOpen = new CountDownLatch(1);
            CountDownLatch incomingChannel = new CountDownLatch(1);
            CountDownLatch answererOpen = new CountDownLatch(1);
            CountDownLatch binaryReceived = new CountDownLatch(8);
            AtomicReference<byte[]> received = new AtomicReference<>();
            AtomicReference<DataChannel> remoteChannel = new AtomicReference<>();
            AtomicReference<Flow.Subscription> messageSubscription = new AtomicReference<>();
            AtomicInteger messageIndex = new AtomicInteger();
            CountDownLatch negotiationNeeded = new CountDownLatch(1);
            CountDownLatch signalingChanged = new CountDownLatch(1);

            answerer.onDataChannel(channel -> {
                remoteChannel.set(channel);
                incomingChannel.countDown();
                channel.onOpen(answererOpen::countDown);
                channel.messages().subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        messageSubscription.set(subscription);
                    }

                    @Override
                    public void onNext(DataChannelMessage message) {
                        try (message) {
                            ByteBuffer data = message.data().orElseThrow();
                            assertTrue(data.isDirect());
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            if (messageIndex.getAndIncrement() == 0) {
                                received.set(bytes);
                            }
                            binaryReceived.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        throw new AssertionError(error);
                    }

                    @Override
                    public void onComplete() {}
                });
            });

            offerer.onNegotiationNeeded(negotiationNeeded::countDown);
            offerer.onSignalingStateChange(ignored -> signalingChanged.countDown());
            DataChannelOptions channelOptions = DataChannelOptions.DEFAULT
                    .withOrdered(false)
                    .withMaxRetransmits(3)
                    .withProtocol("kestara-test-protocol");
            DataChannel channel = offerer.createDataChannel("kestara-test", channelOptions);
            channel.onOpen(offererOpen::countDown);
            assertEquals("kestara-test", channel.label());
            assertEquals("kestara-test-protocol", channel.protocol());
            assertFalse(channel.ordered());
            assertFalse(channel.negotiated());
            assertEquals(null, channel.maxPacketLifeTime());
            assertEquals(3, channel.maxRetransmits());
            assertTrue(channel.id() >= 0);
            assertTrue(negotiationNeeded.await(5, TimeUnit.SECONDS));

            SessionDescription offer = offerer.createOfferAsync()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            offerer.setLocalDescriptionAsync(offer)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertTrue(signalingChanged.await(5, TimeUnit.SECONDS));
            assertEquals(SignalingState.HAVE_LOCAL_OFFER, offerer.signalingState());
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
            DataChannel receivedChannel = remoteChannel.get();
            assertNotNull(receivedChannel);
            assertEquals(channel.id(), receivedChannel.id());
            assertEquals("kestara-test", receivedChannel.label());
            assertEquals("kestara-test-protocol", receivedChannel.protocol());
            assertFalse(receivedChannel.ordered());
            assertFalse(receivedChannel.negotiated());
            assertEquals(null, receivedChannel.maxPacketLifeTime());
            assertEquals(3, receivedChannel.maxRetransmits());

            byte[] payload = {1, 3, 3, 7};
            channel.setBufferedAmountThresholdsAsync(0, 1)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(0, channel.bufferedAmountLowThreshold());
            assertEquals(1, channel.bufferedAmountHighThreshold());
            try (NativeBuffer nativeBuffer = runtime.allocateBuffer(payload.length)) {
                nativeBuffer.buffer().put(payload).flip();
                assertTrue(channel.trySendAsync(nativeBuffer)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS));
                assertFalse(nativeBuffer.isOwned());
            }
            for (int index = 0; index < 7; index++) {
                channel.sendAsync(new byte[] {(byte) index})
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
            }
            Thread.sleep(250);
            assertEquals(0, messageIndex.get(), "Messages arrived without subscriber demand");
            messageSubscription.get().request(8);
            channel.writableAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(binaryReceived.await(10, TimeUnit.SECONDS), "Backpressured messages were not received");
            assertArrayEquals(payload, received.get());
            PeerConnectionStats stats = offerer.getStatsAsync()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertTrue(stats.transport().selectedCandidatePair().isPresent());
            assertFalse(stats.dataChannels().isEmpty());
            assertEquals(2, runtime.diagnostics().reactorThreads());
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
