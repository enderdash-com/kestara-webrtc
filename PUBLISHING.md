# Publish Kestara WebRTC

This guide is for Kestara WebRTC maintainers. It publishes signed Kotlin Multiplatform artifacts to Maven Central and creates a GitHub Release.

## Requirements

Register the `com.enderdash` namespace in the Maven Central Portal. Add these GitHub repository secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `MAVEN_CENTRAL_SIGNING_KEY`
- `MAVEN_CENTRAL_SIGNING_PASSWORD`
- `GRADLE_ENCRYPTION_KEY`

The signing key must use ASCII armor. Publish its public key to a supported key server before the first release.

## Publish a release

1. Open the **Publish Release** workflow in GitHub Actions.
2. Select **Run workflow**.
3. Enter a release version, such as `0.3.0`.
4. Start the workflow from the commit that you want to publish.

The workflow publishes these Kotlin modules:

- Multiplatform root metadata
- JVM
- Linux `x86_64`
- Linux `aarch64`
- macOS Apple silicon
- Windows `x86_64`

The JVM job first builds native libraries for Linux, macOS, and Windows on `x86_64` and `aarch64`. It packages all six libraries in the JVM artifact. Each Kotlin/Native publication builds and links its Rust static library on the correct host.

After Maven Central accepts every publication, the workflow creates a `v<version>` GitHub Release. The release contains generated notes and no binary attachments.

Maven Central does not permit replacement of a released version. Use a new version if a previous publication exists.

## Validate local publications

Publish the host artifacts to the local Maven repository:

```bash
VERSION=0.3.0-SNAPSHOT ./gradlew publishToMavenLocal
```

Host restrictions can disable publications that the current operating system cannot build.

The generated artifacts are under `~/.m2/repository/com/enderdash/`.

Validate a complete JVM native-resource directory before publication:

```bash
./gradlew verifyReleaseNativeResources \
  -PkestaraNativeResources=/path/to/release-native-resources
```
