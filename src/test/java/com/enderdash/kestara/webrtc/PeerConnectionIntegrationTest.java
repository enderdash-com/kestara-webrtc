package com.enderdash.kestara.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

            SessionDescription offer = offerer.createOffer();
            offerer.setLocalDescription(offer);
            answerer.setRemoteDescription(offer);
            offererCandidates.remoteDescriptionReady();

            SessionDescription answer = answerer.createAnswer();
            answerer.setLocalDescription(answer);
            offerer.setRemoteDescription(answer);
            answererCandidates.remoteDescriptionReady();

            assertTrue(offererOpen.await(10, TimeUnit.SECONDS), "Offerer DataChannel did not open");
            assertTrue(incomingChannel.await(10, TimeUnit.SECONDS), "Answerer did not receive a DataChannel");
            assertTrue(answererOpen.await(10, TimeUnit.SECONDS), "Answerer DataChannel did not open");

            byte[] payload = {1, 3, 3, 7};
            channel.send(payload);

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
                remote.addIceCandidate(candidate);
            } else {
                pending.add(candidate);
                if (ready.get() && pending.remove(candidate)) {
                    remote.addIceCandidate(candidate);
                }
            }
        }

        private void remoteDescriptionReady() {
            ready.set(true);
            for (IceCandidate candidate : pending) {
                if (pending.remove(candidate)) {
                    remote.addIceCandidate(candidate);
                }
            }
        }
    }
}
