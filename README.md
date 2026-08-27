# Kestara WebRTC

[![Maven Central](https://img.shields.io/maven-central/v/com.enderdash/kestara-webrtc.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.enderdash/kestara-webrtc)

Kestara WebRTC is a Kotlin Multiplatform library for WebRTC DataChannels. It provides one coroutine-based Kotlin API on JVM and Kotlin/Native. A Rust engine owns the WebRTC protocols and network resources.

> [!IMPORTANT]
> Kestara WebRTC is in early development. Test it with your network and TURN configuration before production use.

The current API is a clean KMP design. It is not compatible with the previous Java API.

## Supported targets

| Kotlin target | Operating system | Architecture | Native boundary |
| --- | --- | --- | --- |
| `jvm` | Linux, macOS, Windows | `x86_64`, `aarch64` | JNI |
| `linuxX64` | Linux | `x86_64` | C ABI |
| `linuxArm64` | Linux | `aarch64` | C ABI |
| `macosArm64` | macOS | Apple silicon | C ABI |
| `mingwX64` | Windows | `x86_64` | C ABI |

The JVM release contains native libraries for all listed JVM platforms. Kotlin/Native artifacts link the Rust engine into the application binary.

## Features

- Suspending peer and DataChannel operations
- `Flow` event streams and `StateFlow` connection state
- SDP offer and answer negotiation
- Trickle ICE with STUN, authenticated TURN, relay-only mode, and ICE restart
- UDP and TCP port ranges, network filters, ICE Lite, mDNS, and one-to-one NAT mappings
- Ordered, unordered, reliable, and partially reliable DataChannels
- Bounded inbound queues that apply backpressure to native delivery
- Text and binary messages
- SCTP buffer, receive window, queue, and message-size limits
- DataChannel and selected ICE candidate pair statistics
- Runtime-owned DTLS certificates with certificate rotation
- Deterministic, bounded shutdown

Media capture, codecs, rendering, and signaling services are outside the project scope.

## Install

Add the dependency to `commonMain`:

```kotlin
kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation("com.enderdash:kestara-webrtc:0.3.0")
    }
  }
}
```

Use Maven Central as a repository. The consumer project needs a Kotlin version that can read the published Kotlin 2.4 metadata. JVM applications need Java 17 or a newer release.

If the JDK restricts native library access, add this JVM option:

```text
--enable-native-access=ALL-UNNAMED
```

## Answer an offer

The application owns signaling. It must exchange descriptions and ICE candidates with the remote peer.

```kotlin
import com.enderdash.kestara.webrtc.IceCandidate
import com.enderdash.kestara.webrtc.IceServer
import com.enderdash.kestara.webrtc.PeerConnection
import com.enderdash.kestara.webrtc.PeerConnectionConfiguration
import com.enderdash.kestara.webrtc.SessionDescription
import com.enderdash.kestara.webrtc.SessionDescriptionType
import com.enderdash.kestara.webrtc.WebRtcRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

suspend fun answerOffer(
  runtime: WebRtcRuntime,
  scope: CoroutineScope,
  remoteOfferSdp: String,
  sendCandidate: suspend (IceCandidate) -> Unit,
  sendAnswer: suspend (SessionDescription) -> Unit,
): PeerConnection {
  val peer = runtime.createPeerConnection(
    PeerConnectionConfiguration(
      iceServers = listOf(
        IceServer("stun:stun.example.com:3478"),
        IceServer.authenticated(
          "username",
          "credential",
          "turn:turn.example.com:3478?transport=udp",
        ),
      ),
    ),
  )
  scope.launch {
    peer.localCandidates.collect { sendCandidate(it) }
  }

  peer.setRemoteDescription(
    SessionDescription(remoteOfferSdp, SessionDescriptionType.OFFER),
  )
  sendAnswer(peer.createAndSetLocalDescription(SessionDescriptionType.ANSWER))
  return peer
}
```

Call `peer.addIceCandidate(candidate)` for each remote trickle candidate. Keep the returned peer and its runtime open while the session is active. Close both when the session ends.

## Use a DataChannel

Create a channel and collect messages in a coroutine:

```kotlin
val channel = peer.createDataChannel(
  label = "control",
  options = DataChannelOptions(
    ordered = false,
    maxRetransmits = 2,
  ),
)

launch {
  channel.messages.collect { message ->
    when (message) {
      is DataChannelMessage.Text -> handleText(message.value)
      is DataChannelMessage.Binary -> handleBinary(message.data)
    }
  }
}

channel.send("ready")
channel.send(byteArrayOf(1, 2, 3))
```

Collect `peer.incomingDataChannels` to receive channels created by the remote peer. Collect `channel.events` for open, close, error, and buffered-amount events.

Each event stream is a work queue. One collector receives each event. Do not use multiple collectors when every collector must see every event. The DataChannel message queue uses `SctpOptions.receiveQueueCapacity`. When the queue is full, Kestara pauses native event delivery until the collector makes room.

## Observe state

Connection state uses hot `StateFlow` values:

```kotlin
peer.state.collect { state -> updateConnectionState(state) }
peer.iceConnectionState.collect { state -> updateIceState(state) }
channel.state.collect { state -> updateChannelState(state) }
```

The other event sources are hot `Flow` views over bounded or buffered channels:

- `peer.localCandidates`
- `peer.localDescriptions`
- `peer.incomingDataChannels`
- `peer.negotiationNeeded`
- `channel.messages`
- `channel.events`

## Configure lifecycle and transport

Use one runtime for each independently owned application, service, plugin, or test lifecycle.

```kotlin
val runtime = WebRtcRuntime.create(
  WebRtcRuntimeOptions(
    workerThreads = 4,
    reactorThreads = 1,
    shutdownTimeout = 10.seconds,
  ),
)

val configuration = PeerConnectionConfiguration(
  minPort = 40_000,
  maxPort = 40_100,
  iceOptions = IceOptions(
    networkTypes = setOf(IceNetworkType.UDP4, IceNetworkType.TCP4),
    candidatePoolSize = 1,
  ),
  sctpOptions = SctpOptions(
    sendBufferLimit = 8 * 1024 * 1024,
    receiveBufferSize = 512 * 1024,
    maximumMessageSize = 128 * 1024,
    receiveQueueCapacity = 64,
  ),
)
```

Close child resources before their owner:

1. Close each `DataChannel`.
2. Close each `PeerConnection`.
3. Close the `WebRtcRuntime`.

All close operations suspend until native cleanup finishes or the configured timeout expires. Runtime shutdown also closes any remaining child resources.

## Build and test

Contributors need Java 17 and Rust 1.94.1. Run the full host verification suite:

```bash
./gradlew check
```

Build a specific Kotlin/Native target:

```bash
./gradlew linkDebugTestLinuxX64
./gradlew compileKotlinLinuxArm64
./gradlew linkDebugTestMacosArm64
./gradlew linkDebugTestMingwX64
```

Gradle builds the required Rust static library before cinterop. Build each Kotlin/Native target on its matching host and architecture.

## Architecture

`commonMain` owns the public API, coroutine lifecycle, event routing, validation, and statistics decoding. The JVM adapter uses a small JNI binding. Kotlin/Native uses a stable C ABI and links the Rust static library through cinterop.

Each Rust runtime owns its WebRTC state, Tokio workers, network resources, command queue, and native handles. Rust panics do not cross JNI or the C ABI. Application code does not run on protocol threads.

The Kotlin and Rust layers declare a native ABI version. Runtime creation stops when the versions differ. Public types do not expose JNI, cinterop, Rust, EnderDash signaling, RPC, or Minecraft details.

## Status

Shared integration tests run the same API on JVM and Kotlin/Native. They create two local peers, exchange an offer, answer, and trickle ICE candidates, then open a partially reliable DataChannel. The tests verify binary delivery and statistics.

The project still needs broader public-network tests, browser compatibility tests, and long shutdown-cycle soak tests before a stable release.

## License

Kestara WebRTC is available under the Apache License 2.0 or the MIT License, at your option.
