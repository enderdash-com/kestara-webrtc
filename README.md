# Alloy WebRTC

Alloy WebRTC is a WebRTC DataChannel library for Java. A Rust runtime provides the native protocol implementation.

> [!IMPORTANT]
> Alloy WebRTC is in early development. The current code establishes the native ABI, loader, build, and publication structure.

## Goals

- Provide an idiomatic Java API for WebRTC DataChannels.
- Support SDP offer and answer negotiation.
- Support trickle ICE with STUN and TURN servers.
- Keep native callbacks outside application threads.
- Provide explicit ownership and bounded shutdown.
- Distribute native libraries as prebuilt Maven artifacts.

Media capture, codecs, rendering, and signaling services are outside the initial scope.

## Requirements

Applications need Java 17 or a newer Java release.

Contributors also need Rust 1.85 or a newer Rust release. Application builds do not compile Rust after a release artifact exists.

## Install

The planned Maven coordinate is:

```kotlin
implementation("com.enderdash:alloy-webrtc:0.1.0")
```

No public release exists yet. Release artifacts will contain the Java API and supported native libraries.

## Build

Run the complete verification suite:

```bash
./gradlew check
```

Create the JAR:

```bash
./gradlew build
```

Gradle builds the Rust library for the current operating system. It then adds the library to the generated JAR resources.

## Architecture

The Java layer owns the public API and application event dispatch. The Rust layer will own WebRTC protocol state and network processing.

JNI provides the initial native boundary. Each Java and native release declares an ABI version. Library startup stops if these versions differ.

The public API will not contain EnderDash signaling, RPC, Minecraft, or Rust implementation types.

## Project status

The repository currently contains:

- A Java 17 module named `com.enderdash.alloy.webrtc`.
- A Rust `cdylib` with a versioned JNI ABI.
- Native resource loading for Linux, macOS, and Windows.
- Gradle publication metadata for `com.enderdash:alloy-webrtc`.
- Java and Rust verification tasks.

Peer connections and DataChannels are the next implementation milestone.

## License

Alloy WebRTC is available under the Apache License 2.0 or the MIT License, at your option.
