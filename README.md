# Kestara WebRTC

Kestara WebRTC is a WebRTC DataChannel library for Java. It provides a small Java API over the Rust `webrtc` implementation.

> [!IMPORTANT]
> Kestara WebRTC is in early development. Test it with your own network and TURN setup before you use it in production.

## Features

- SDP offer and answer negotiation
- Trickle ICE candidates
- STUN and authenticated TURN servers
- Direct or relay-only ICE policies
- Optional UDP port ranges
- Ordered, unordered, reliable, and partially reliable DataChannels
- Text and binary messages
- Explicit resource ownership and bounded native operations
- Ordered Java callbacks on a configurable executor

Media capture, codecs, rendering, and signaling services are outside the current scope.

## Requirements

Applications need Java 17 or a newer Java release.

Contributors also need Rust 1.94.1. Application builds do not compile Rust after you publish a JAR that contains the native library for their platform.

## Install

The stable Maven coordinate will be:

```kotlin
implementation("com.enderdash:kestara-webrtc:0.1.0")
```

No public release exists yet. Development snapshots are available from JitPack after a successful build:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.enderdash-com:kestara-webrtc:main-SNAPSHOT")
}
```

Use a commit hash instead of `main-SNAPSHOT` when a build must be reproducible. JitPack builds contain the native library for its build platform.

Maven Central releases contain native libraries for Linux, macOS, and Windows. Each release supports `x86_64` and `aarch64` systems.

## Quick start

```java
import com.enderdash.kestara.webrtc.IceCandidate;
import com.enderdash.kestara.webrtc.IceServer;
import com.enderdash.kestara.webrtc.PeerConnection;
import com.enderdash.kestara.webrtc.PeerConnectionConfiguration;
import com.enderdash.kestara.webrtc.SessionDescription;
import com.enderdash.kestara.webrtc.SessionDescriptionType;
import java.util.List;

var configuration = PeerConnectionConfiguration.DEFAULT.withIceServers(List.of(
        IceServer.of("stun:stun.example.com:3478"),
        IceServer.authenticated(
                "username",
                "credential",
                "turn:turn.example.com:3478?transport=udp")));

try (var peer = PeerConnection.create(configuration)) {
    peer.onLocalCandidate(candidate -> sendCandidateToRemotePeer(candidate));
    peer.onDataChannel(channel -> configureChannel(channel));

    peer.setRemoteDescription(new SessionDescription(remoteOffer, SessionDescriptionType.OFFER));
    SessionDescription answer = peer.setLocalDescription(SessionDescriptionType.ANSWER);
    sendAnswerToRemotePeer(answer);

    peer.addIceCandidate(new IceCandidate(remoteCandidate, "0", 0));
}
```

The application provides signaling. It must send local descriptions and ICE candidates to the remote peer.

Set each callback before an operation that can produce its event. Incoming DataChannel callbacks can register message and lifecycle handlers before Kestara reports an already-open channel.

## Lifecycle and threads

`PeerConnection` and `DataChannel` implement `AutoCloseable`. Close each object when it is no longer needed. Call `KestaraWebRtc.shutdown()` during application shutdown to close remaining peers and release the shared native runtime.

Rust protocol tasks run on a Kestara-owned Tokio runtime. One daemon Java thread receives native events. Kestara sends each peer's callbacks to its configured Java executor in event order. Application callbacks do not run on Rust protocol threads.

Blocking Java operations use the configured operation timeout. Native runtime shutdown also has a fixed upper time limit. These limits prevent a stalled protocol task from blocking Java shutdown without a bound.

## Build

Run the complete verification suite:

```bash
./gradlew check
```

Create the host-platform JAR:

```bash
./gradlew build
```

Gradle builds the Rust library for the current operating system and architecture. It adds the library under `META-INF/native` in the JAR.

Maintainers can publish a signed cross-platform release with the [Maven Central publishing workflow](./PUBLISHING.md).

## Architecture

The Java layer owns the public API and application event dispatch. The Rust layer owns WebRTC protocol state, networking, and native resource cleanup. JNI is a small handle-based boundary between these layers.

Each Java and native release declares an ABI version. Library startup stops when these versions differ. The public API does not expose EnderDash signaling, RPC, Minecraft, JNI, or Rust implementation types.

## Status

The current implementation has an end-to-end integration test that creates two local peers, exchanges an offer, answer, and trickle ICE candidates, opens an ordered DataChannel, and sends a binary message.

Before the first stable release, the project still needs broader network tests. It also needs browser compatibility and shutdown-cycle soak tests.

## License

Kestara WebRTC is available under the Apache License 2.0 or the MIT License, at your option.
