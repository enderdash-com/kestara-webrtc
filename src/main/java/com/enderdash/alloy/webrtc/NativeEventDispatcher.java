package com.enderdash.alloy.webrtc;

import com.enderdash.alloy.webrtc.internal.NativeBindings;
import com.enderdash.alloy.webrtc.internal.NativeEvent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Polls native events on one daemon thread and routes them to peer callback executors. */
final class NativeEventDispatcher {
    private static final System.Logger LOGGER =
            System.getLogger(NativeEventDispatcher.class.getName());
    private static final ConcurrentMap<Long, PeerConnection> PEERS = new ConcurrentHashMap<>();
    private static final Object LIFECYCLE_LOCK = new Object();
    private static volatile boolean running;
    private static Thread thread;

    private NativeEventDispatcher() {}

    static void register(long handle, PeerConnection peer) {
        PEERS.put(handle, peer);
        start();
    }

    static void unregister(long handle) {
        PEERS.remove(handle);
    }

    static void closeAll() {
        for (PeerConnection peer : PEERS.values().toArray(PeerConnection[]::new)) {
            peer.closeForShutdown();
        }
    }

    static void stop() {
        Thread current;
        synchronized (LIFECYCLE_LOCK) {
            if (!running) {
                return;
            }
            running = false;
            NativeBindings.wakeEventLoop();
            current = thread;
            thread = null;
        }
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(2_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void start() {
        synchronized (LIFECYCLE_LOCK) {
            if (running) {
                return;
            }
            running = true;
            thread = new Thread(NativeEventDispatcher::run, "alloy-webrtc-events");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void run() {
        while (running) {
            NativeEvent event = NativeBindings.pollEvent(1_000);
            if (event == null) {
                continue;
            }
            PeerConnection peer = PEERS.get(event.peerHandle());
            if (peer != null) {
                try {
                    peer.handleNativeEvent(event);
                } catch (RuntimeException error) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "Failed to dispatch an Alloy WebRTC event",
                            error);
                }
            }
        }
    }
}
