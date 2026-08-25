package com.enderdash.kestara.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PeerConnectionIntegrationTest {
    @AfterEach
    void stopNativeRuntime() {
        KestaraWebRtc.shutdown();
    }

    @Test
    void createsAndClosesPeerConnection() {
        try (PeerConnection peer = PeerConnection.create(PeerConnectionConfiguration.DEFAULT)) {
            assertNotNull(peer);
        }
    }

    @Test
    void shutdownClosesRemainingPeersAndAllowsRuntimeRestart() {
        PeerConnection first = PeerConnection.create(PeerConnectionConfiguration.DEFAULT);

        KestaraWebRtc.shutdown();

        assertTrue(first.state() == PeerConnectionState.CLOSED);
        try (PeerConnection second =
                PeerConnection.create(PeerConnectionConfiguration.DEFAULT)) {
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
    void exchangesBinaryDataOverAnOrderedChannel() throws Exception {
        ExecutorService callbacks = Executors.newCachedThreadPool();
        PeerConnectionConfiguration configuration = PeerConnectionConfiguration.DEFAULT
                .withCallbackExecutor(callbacks)
                .withOperationTimeout(Duration.ofSeconds(5));
        try (PeerConnection offerer = PeerConnection.create(configuration);
                PeerConnection answerer = PeerConnection.create(configuration)) {
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
