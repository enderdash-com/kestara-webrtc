# Publish Kestara WebRTC

This guide is for Kestara WebRTC maintainers. It publishes one signed, cross-platform JAR to Maven Central.

## Requirements

Register the `com.enderdash` namespace in the Maven Central Portal. Add the following GitHub repository secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `MAVEN_CENTRAL_SIGNING_KEY`
- `MAVEN_CENTRAL_SIGNING_PASSWORD`
- `GRADLE_ENCRYPTION_KEY`

The signing key must use ASCII armor. Publish its public key to a supported key server before the first release.

## Publish a release

1. Open the **Publish to Maven Central** workflow in GitHub Actions.
2. Select **Run workflow**.
3. Enter a release version, such as `0.1.0`.
4. Start the workflow from the commit that you want to publish.

The workflow builds six native libraries:

- Linux `x86_64` and `aarch64`
- macOS `x86_64` and `aarch64`
- Windows `x86_64` and `aarch64`

Each target uses an isolated job on its operating system. The Linux ARM64 job uses the GNU cross-compiler. The Windows ARM64 job uses the MSVC cross-compiler. Both macOS targets use native GitHub runners.

The publish job downloads and verifies all six native resources. It combines them with the Java classes, sources, Javadoc, POM, checksums, and signatures. It then releases the deployment automatically.

Maven Central does not permit replacement of a released version. Use a new version if a previous publication exists.

## Validate local publication metadata

Publish the host-platform artifact to the local Maven repository:

```bash
VERSION=0.1.0-SNAPSHOT ./gradlew publishToMavenLocal
```

The generated artifacts are under `~/.m2/repository/com/enderdash/kestara-webrtc/0.1.0-SNAPSHOT/`.

Validate an assembled release directory before publication:

```bash
./gradlew verifyReleaseNativeResources \
  -PkestaraNativeResources=/path/to/release-native-resources
```
