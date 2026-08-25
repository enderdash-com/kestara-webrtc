import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
  `java-library`
  `maven-publish`
}

group = "com.enderdash"
version = providers.environmentVariable("VERSION").orElse("0.1.0-SNAPSHOT").get()

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
  withJavadocJar()
  withSourcesJar()
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
}

val nativeOs = when {
  System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
  System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
  System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
  else -> error("Unsupported build operating system: ${System.getProperty("os.name")}")
}

val nativeArch = when (System.getProperty("os.arch").lowercase()) {
  "amd64", "x86_64" -> "x86_64"
  "aarch64", "arm64" -> "aarch64"
  else -> error("Unsupported build architecture: ${System.getProperty("os.arch")}")
}

val nativeLibraryName = when (nativeOs) {
  "windows" -> "alloy_webrtc_native.dll"
  "macos" -> "liballoy_webrtc_native.dylib"
  else -> "liballoy_webrtc_native.so"
}

val nativeManifest = layout.projectDirectory.file("native/Cargo.toml")
val nativeLibrary = layout.projectDirectory.file("native/target/release/$nativeLibraryName")
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")

val buildRustNative = tasks.register<Exec>("buildRustNative") {
  group = "build"
  description = "Builds the Rust native library for the current platform."
  workingDir(layout.projectDirectory.dir("native"))
  commandLine("cargo", "build", "--locked", "--release")
  inputs.files(fileTree("native") {
    include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
    exclude("target/**")
  })
  outputs.file(nativeLibrary)
}

val prepareNativeResources = tasks.register<Sync>("prepareNativeResources") {
  dependsOn(buildRustNative)
  from(nativeLibrary)
  into(generatedNativeResources.map { it.dir("META-INF/native/$nativeOs/$nativeArch") })
}

tasks.processResources {
  dependsOn(prepareNativeResources)
  from(generatedNativeResources)
}

val cargoFmt = tasks.register<Exec>("cargoFmt") {
  group = "verification"
  description = "Checks Rust formatting."
  workingDir(layout.projectDirectory.dir("native"))
  commandLine("cargo", "fmt", "--", "--check")
}

val cargoClippy = tasks.register<Exec>("cargoClippy") {
  group = "verification"
  description = "Runs Rust static analysis."
  workingDir(layout.projectDirectory.dir("native"))
  commandLine("cargo", "clippy", "--all-targets", "--locked", "--", "-D", "warnings")
}

val cargoTest = tasks.register<Exec>("cargoTest") {
  group = "verification"
  description = "Runs Rust unit tests."
  workingDir(layout.projectDirectory.dir("native"))
  commandLine("cargo", "test", "--locked")
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(17)
  options.encoding = Charsets.UTF_8.name()
  options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

tasks.check {
  dependsOn(cargoFmt, cargoClippy, cargoTest)
}

tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      artifactId = "alloy-webrtc"

      pom {
        name.set("Alloy WebRTC")
        description.set("A WebRTC DataChannel library for Java, powered by Rust.")
        url.set("https://github.com/enderdash-com/alloy-webrtc")
        licenses {
          license {
            name.set("Apache License 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
          }
          license {
            name.set("MIT License")
            url.set("https://opensource.org/license/mit")
            distribution.set("repo")
          }
        }
        developers {
          developer {
            organization.set("EnderDash")
            organizationUrl.set("https://enderdash.com")
          }
        }
        scm {
          connection.set("scm:git:https://github.com/enderdash-com/alloy-webrtc.git")
          developerConnection.set("scm:git:ssh://git@github.com/enderdash-com/alloy-webrtc.git")
          url.set("https://github.com/enderdash-com/alloy-webrtc")
        }
      }
    }
  }
}
