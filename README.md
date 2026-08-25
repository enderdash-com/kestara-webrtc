# Kestara WebRTC

[![Maven Central](https://img.shields.io/maven-central/v/com.enderdash/kestara-webrtc.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.enderdash/kestara-webrtc)

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
- Isolated runtimes with configurable Rust worker counts
- Non-blocking operations through `CompletionStage`
- Deterministic, bounded native shutdown
- Ordered Java callbacks on a configurable executor

Media capture, codecs, rendering, and signaling services are outside the current scope.

## Requirements

Applications need Java 17 or a newer Java release.

Contributors also need Rust 1.94.1. Application builds do not compile Rust after you publish a JAR that contains the native library for their platform.

## Install

Add Kestara WebRTC from Maven Central.

### Gradle

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.enderdash:kestara-webrtc:0.1.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.enderdash</groupId>
    <artifactId>kestara-webrtc</artifactId>
    <version>0.1.0</version>
</dependency>
```

Maven Central releases contain native libraries for Linux, macOS, and Windows. Each release supports `x86_64` and `aarch64` systems.

## Quick start

```java
import com.enderdash.kestara.webrtc.IceCandidate;
import com.enderdash.kestara.webrtc.IceServer;
import com.enderdash.kestara.webrtc.PeerConnection;
import com.enderdash.kestara.webrtc.PeerConnectionConfiguration;
import com.enderdash.kestara.webrtc.SessionDescription;
import com.enderdash.kestara.webrtc.SessionDescriptionType;
import com.enderdash.kestara.webrtc.WebRtcRuntime;
import java.util.List;

var configuration = PeerConnectionConfiguration.DEFAULT.withIceServers(List.of(
        IceServer.of("stun:stun.example.com:3478"),
        IceServer.authenticated(
                "username",
                "credential",
                "turn:turn.example.com:3478?transport=udp")));

try (WebRtcRuntime runtime = WebRtcRuntime.create();
        PeerConnection peer = runtime.createPeerConnection(configuration)) {
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

Use one `WebRtcRuntime` for each independently owned application, plugin, or test lifecycle. A runtime owns its Rust worker pool, native handles, event thread, and peer connections. Closing it gives accepted operations time to finish, closes its peers, and joins the native worker threads. It cancels remaining work when the shutdown timeout expires.

Configure its worker count and shutdown bound when needed:

```java
var options = WebRtcRuntimeOptions.DEFAULT
        .withWorkerThreads(4)
        .withShutdownTimeout(Duration.ofSeconds(10));

try (WebRtcRuntime runtime = WebRtcRuntime.create(options)) {
    WebRtcRuntimeDiagnostics diagnostics = runtime.diagnostics();
}
```

## Asynchronous operations

Native peer and DataChannel operations return `CompletionStage` variants. They enqueue work on the owning Rust runtime instead of blocking the Java caller.

```java
peer.setRemoteDescriptionAsync(remoteDescription)
        .thenCompose(ignored -> peer.setLocalDescriptionAsync(SessionDescriptionType.ANSWER))
        .thenAccept(this::sendAnswerToRemotePeer)
        .exceptionally(error -> {
            reportNegotiationFailure(error);
            return null;
        });

CompletionStage<Void> sent = channel.sendAsync(byteBuffer);
CompletionStage<Void> closed = peer.closeAsync();
```

The synchronous methods call the same asynchronous commands and wait up to the peer's configured operation timeout. Prefer the asynchronous methods on server request threads and callback executors.

Rust protocol tasks run on runtime-owned Tokio workers. One daemon Java thread per runtime receives native events. Kestara sends each peer's callbacks to its configured Java executor in event order. Application callbacks do not run on Rust protocol threads.

Blocking Java operations use the configured operation timeout. Runtime shutdown uses `WebRtcRuntimeOptions.shutdownTimeout()`.

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

Maintainers can publish a signed cross-platform release with the [release workflow](./PUBLISHING.md).

## Architecture

The Java layer owns the public API and application event dispatch. Each Rust runtime owns its WebRTC state, networking, command queue, and native resource cleanup. JNI is a small handle-based boundary between these layers.

Each Java and native release declares an ABI version. Library startup stops when these versions differ. The public API does not expose EnderDash signaling, RPC, Minecraft, JNI, or Rust implementation types.

## Status

The current implementation has an end-to-end integration test that creates two local peers, exchanges an offer, answer, and trickle ICE candidates, opens an ordered DataChannel, and sends a binary message.

Before the first stable release, the project still needs broader network tests. It also needs browser compatibility and shutdown-cycle soak tests.

## License

Kestara WebRTC is available under the Apache License 2.0 or the MIT License, at your option.
