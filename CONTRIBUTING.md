# Contributing

Kestara WebRTC accepts focused bug reports and pull requests.

## Prepare the repository

Install these tools:

- Java 17 or a newer release
- Rust 1.94.1
- The C toolchain for each Kotlin/Native target that you build

The Gradle wrapper downloads Gradle and the Kotlin/Native compiler.

## Verify a change

Run all checks before you open a pull request:

```bash
./gradlew check
```

This command compiles the shared Kotlin API, the JVM adapter, the host Kotlin/Native target, and Rust. It runs shared integration tests, Rust tests, Rustfmt, and Clippy.

For target-specific changes, compile each affected target on its matching host:

```bash
./gradlew compileKotlinLinuxArm64
./gradlew compileKotlinMacosArm64
./gradlew compileKotlinMingwX64
```

The GitHub Actions matrix verifies Linux, macOS, and Windows. It also cross-compiles `linuxArm64`.

## Keep platform boundaries small

Put public behavior and validation in `commonMain`. Use `jvmMain` and `nativeMain` only for platform interop. Do not expose JNI, C pointers, cinterop types, or Rust types in the public API.

Keep the JNI and C ABI functions handle-based. Catch Rust panics before they cross either boundary. Do not invoke application code on Rust protocol threads.

## Commit messages

Use Conventional Commit format:

```text
<type>(<scope>): <description>
```

Use an imperative and concise description. Add a commit body when the reason is not clear from the description.

## Pull requests

Keep each pull request focused on one change. Describe the behavior, the reason for the change, and the verification that you completed.
