import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File

plugins {
  `java-library`
  id("com.vanniktech.maven.publish") version "0.37.0"
}

group = providers.environmentVariable("GROUP").orElse("com.enderdash").get()
version = providers.environmentVariable("VERSION").orElse("0.1.0-SNAPSHOT").get()

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
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
  "windows" -> "kestara_webrtc_native.dll"
  "macos" -> "libkestara_webrtc_native.dylib"
  else -> "libkestara_webrtc_native.so"
}

val nativeManifest = layout.projectDirectory.file("native/Cargo.toml")
val nativeTarget = providers.gradleProperty("kestaraNativeTarget")
val releaseNativeArch = providers.gradleProperty("kestaraNativeArch").orElse(nativeArch)
val nativeLibrary = nativeTarget
  .map { layout.projectDirectory.file("native/target/$it/release/$nativeLibraryName") }
  .orElse(layout.projectDirectory.file("native/target/release/$nativeLibraryName"))
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")
val releaseNativeResources = providers.gradleProperty("kestaraNativeResources")
  .map { layout.projectDirectory.dir(it) }

val buildRustNative = tasks.register<Exec>("buildRustNative") {
  group = "build"
  description = "Builds the Rust native library for the current platform."
  workingDir(layout.projectDirectory.dir("native"))
  commandLine(buildList {
    addAll(listOf("cargo", "build", "--locked", "--release"))
    nativeTarget.orNull?.let {
      addAll(listOf("--target", it))
    }
  })
  inputs.files(fileTree("native") {
    include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
    exclude("target/**")
  })
  inputs.file(layout.projectDirectory.file("rust-toolchain.toml"))
  inputs.property("nativeTarget", nativeTarget.orElse("host"))
  outputs.file(nativeLibrary)
}

val prepareNativeResources = tasks.register<Sync>("prepareNativeResources") {
  dependsOn(buildRustNative)
  from(nativeLibrary)
  into(generatedNativeResources.map {
    it.dir("META-INF/native/$nativeOs/${releaseNativeArch.get()}")
  })
}

tasks.processResources {
  if (releaseNativeResources.isPresent) {
    from(releaseNativeResources)
  } else {
    dependsOn(prepareNativeResources)
    from(generatedNativeResources)
  }
}

val requiredReleaseNativeResources = listOf(
  "META-INF/native/linux/x86_64/libkestara_webrtc_native.so",
  "META-INF/native/linux/aarch64/libkestara_webrtc_native.so",
  "META-INF/native/macos/x86_64/libkestara_webrtc_native.dylib",
  "META-INF/native/macos/aarch64/libkestara_webrtc_native.dylib",
  "META-INF/native/windows/x86_64/kestara_webrtc_native.dll",
  "META-INF/native/windows/aarch64/kestara_webrtc_native.dll",
)

val verifyReleaseNativeResources = tasks.register("verifyReleaseNativeResources") {
  group = "verification"
  description = "Verifies that a Maven Central release contains every supported native library."
  inputs.property("requiredNativeResources", requiredReleaseNativeResources)
  inputs.property(
    "resourceDirectory",
    releaseNativeResources.map { it.asFile.absolutePath }.orElse(""),
  )
  releaseNativeResources.orNull?.let { inputs.dir(it) }

  doLast {
    val resourceDirectoryPath = inputs.properties.getValue("resourceDirectory") as String
    if (resourceDirectoryPath.isBlank()) {
      throw GradleException(
        "Set -PkestaraNativeResources to the directory that contains the release native resources."
      )
    }
    val resourceDirectory = File(resourceDirectoryPath)
    val requiredResources = inputs.properties.getValue("requiredNativeResources") as List<*>
    val missingResources = requiredResources.filterIsInstance<String>().filterNot {
      resourceDirectory.resolve(it).isFile
    }
    if (missingResources.isNotEmpty()) {
      throw GradleException(
        "Missing native resources for the Maven Central release:\n" +
          missingResources.joinToString(separator = "\n") { "- $it" }
      )
    }
  }
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

tasks.jar {
  from(layout.projectDirectory.file("LICENSE")) {
    into("META-INF")
  }
  from(layout.projectDirectory.file("LICENSE-APACHE")) {
    into("META-INF")
  }
  from(layout.projectDirectory.file("LICENSE-MIT")) {
    into("META-INF")
  }
}

tasks.matching {
  it.name == "publishMavenPublicationToMavenCentralRepository" ||
    it.name == "publishAllPublicationsToMavenCentralRepository"
}.configureEach {
  dependsOn(verifyReleaseNativeResources)
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()

  configure(JavaLibrary(
    javadocJar = JavadocJar.Javadoc(),
    sourcesJar = SourcesJar.Sources(),
  ))

  coordinates(project.group.toString(), "kestara-webrtc", project.version.toString())

  pom {
    name = "Kestara WebRTC"
    description = "A WebRTC DataChannel library for Java, powered by Rust."
    url = "https://github.com/enderdash-com/kestara-webrtc"
    inceptionYear = "2026"

    licenses {
      license {
        name = "The Apache License, Version 2.0"
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        distribution = "repo"
      }
      license {
        name = "The MIT License"
        url = "https://opensource.org/license/mit"
        distribution = "repo"
      }
    }

    organization {
      name = "EnderDash"
      url = "https://enderdash.com"
    }

    developers {
      developer {
        id = "AlexProgrammerDE"
        name = "Alexander Kremer"
        url = "https://github.com/AlexProgrammerDE"
      }
    }

    contributors {}

    scm {
      url = "https://github.com/enderdash-com/kestara-webrtc"
      connection = "scm:git:git://github.com/enderdash-com/kestara-webrtc.git"
      developerConnection = "scm:git:ssh://git@github.com/enderdash-com/kestara-webrtc.git"
    }

    issueManagement {
      system = "GitHub"
      url = "https://github.com/enderdash-com/kestara-webrtc/issues"
    }

    ciManagement {
      system = "GitHub Actions"
      url = "https://github.com/enderdash-com/kestara-webrtc/actions"
    }

    distributionManagement {
      downloadUrl = "https://central.sonatype.com/artifact/com.enderdash/kestara-webrtc"
    }
  }
}
