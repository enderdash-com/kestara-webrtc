import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
  kotlin("multiplatform") version "2.4.10"
  kotlin("plugin.serialization") version "2.4.10"
  id("com.vanniktech.maven.publish") version "0.37.0"
}

group = providers.environmentVariable("GROUP").orElse("com.enderdash").get()
version = providers.environmentVariable("VERSION").orElse("0.3.0-SNAPSHOT").get()

repositories {
  mavenCentral()
}

val nativeHeaderDirectory = layout.projectDirectory.dir("native/include")

fun KotlinNativeTarget.configureKestaraInterop(
  rustTarget: String,
  linkerOptions: List<String> = emptyList(),
) {
  val targetDirectory = layout.projectDirectory.dir("native/target/$rustTarget/release")
  val staticLibraryName = "libkestara_webrtc_native.a"
  val staticLibrary = targetDirectory.file(staticLibraryName)
  val taskSuffix = name.replaceFirstChar(Char::uppercaseChar)
  val buildTask = tasks.register<Exec>("buildRust$taskSuffix") {
    group = "build"
    description = "Builds the Rust library for the $name Kotlin/Native target."
    workingDir(layout.projectDirectory.dir("native"))
    environment("KESTARA_LIBRARY_VERSION", project.version.toString())
    commandLine("cargo", "build", "--locked", "--release", "--target", rustTarget)
    inputs.files(fileTree("native") {
      include("Cargo.toml", "Cargo.lock", "include/**/*.h", "src/**/*.rs")
      exclude("target/**")
    })
    inputs.file(layout.projectDirectory.file("rust-toolchain.toml"))
    inputs.property("libraryVersion", project.version.toString())
    outputs.file(staticLibrary)
  }

  binaries.all {
    linkerOpts(*linkerOptions.toTypedArray())
  }

  compilations.getByName("main").cinterops.create("kestara") {
    definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/kestara.def"))
    includeDirs(nativeHeaderDirectory)
    extraOpts(
      "-libraryPath", targetDirectory.asFile.absolutePath,
      "-staticLibrary", staticLibraryName,
    )
    tasks.named(interopProcessingTaskName).configure {
      dependsOn(buildTask)
    }
  }
}

kotlin {
  explicitApi()

  jvm {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
      allWarningsAsErrors.set(true)
    }
    testRuns["test"].executionTask.configure {
      useJUnitPlatform()
    }
  }

  linuxX64 {
    configureKestaraInterop("x86_64-unknown-linux-gnu")
  }
  linuxArm64 {
    configureKestaraInterop("aarch64-unknown-linux-gnu")
  }
  macosArm64 {
    configureKestaraInterop("aarch64-apple-darwin")
  }
  mingwX64 {
    configureKestaraInterop(
      rustTarget = "x86_64-pc-windows-gnu",
      linkerOptions = listOf("-luserenv", "-lntdll", "-liphlpapi"),
    )
  }

  sourceSets {
    commonMain.dependencies {
      api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
      implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
    }
    nativeMain.dependencies {
      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    }
    jvmTest.dependencies {
      implementation("org.junit.jupiter:junit-jupiter:6.1.2")
      runtimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
    }
  }
}

val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val jvmNativeOs = when {
  hostOs.contains("windows") -> "windows"
  hostOs.contains("mac") -> "macos"
  hostOs.contains("linux") -> "linux"
  else -> error("Unsupported build operating system: $hostOs")
}
val jvmNativeArch = when (hostArch) {
  "amd64", "x86_64" -> "x86_64"
  "aarch64", "arm64" -> "aarch64"
  else -> error("Unsupported build architecture: $hostArch")
}
val runnableNativeTarget = when (jvmNativeOs to jvmNativeArch) {
  "linux" to "x86_64" -> "LinuxX64"
  "linux" to "aarch64" -> "LinuxArm64"
  "macos" to "aarch64" -> "MacosArm64"
  "windows" to "x86_64" -> "MingwX64"
  else -> null
}
val nativeTargetTokens = listOf("LinuxX64", "LinuxArm64", "MacosArm64", "MingwX64")
val unavailableNativeTargets = nativeTargetTokens - setOfNotNull(runnableNativeTarget)

tasks.configureEach {
  unavailableNativeTargets.forEach { token ->
    val targetName = token.replaceFirstChar(Char::lowercaseChar)
    if (
      name == "buildRust$token" ||
      name == "cinteropKestara$token" ||
      name == "compileKotlin$token" ||
      name == "compileTestKotlin$token" ||
      name == "linkDebugTest$token" ||
      name == "${targetName}MainKlibrary" ||
      name == "${targetName}ProcessResources" ||
      name == "${targetName}Test"
    ) {
      enabled = false
    }
  }
}
val jvmRustTarget = providers.gradleProperty("kestaraNativeTarget")
val jvmReleaseArch = providers.gradleProperty("kestaraNativeArch").orElse(jvmNativeArch)
val jvmLibraryName = when (jvmNativeOs) {
  "windows" -> "kestara_webrtc_native.dll"
  "macos" -> "libkestara_webrtc_native.dylib"
  else -> "libkestara_webrtc_native.so"
}
val jvmNativeLibrary = jvmRustTarget
  .map { layout.projectDirectory.file("native/target/$it/release/$jvmLibraryName") }
  .orElse(layout.projectDirectory.file("native/target/release/$jvmLibraryName"))
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")
val releaseNativeResources = providers.gradleProperty("kestaraNativeResources")
  .map { layout.projectDirectory.dir(it) }
val requiredReleaseNativeLibraries = listOf(
  "META-INF/native/linux/x86_64/libkestara_webrtc_native.so",
  "META-INF/native/linux/aarch64/libkestara_webrtc_native.so",
  "META-INF/native/macos/x86_64/libkestara_webrtc_native.dylib",
  "META-INF/native/macos/aarch64/libkestara_webrtc_native.dylib",
  "META-INF/native/windows/x86_64/kestara_webrtc_native.dll",
  "META-INF/native/windows/aarch64/kestara_webrtc_native.dll",
)

val buildRustJvm = tasks.register<Exec>("buildRustJvm") {
  group = "build"
  description = "Builds the Rust JNI library for the current platform."
  workingDir(layout.projectDirectory.dir("native"))
  environment("KESTARA_LIBRARY_VERSION", project.version.toString())
  commandLine(buildList {
    addAll(listOf("cargo", "build", "--locked", "--release"))
    jvmRustTarget.orNull?.let { addAll(listOf("--target", it)) }
  })
  inputs.files(fileTree("native") {
    include("Cargo.toml", "Cargo.lock", "include/**/*.h", "src/**/*.rs")
    exclude("target/**")
  })
  inputs.file(layout.projectDirectory.file("rust-toolchain.toml"))
  inputs.property("nativeTarget", jvmRustTarget.orElse("host"))
  inputs.property("libraryVersion", project.version.toString())
  outputs.file(jvmNativeLibrary)
}

val prepareNativeResources = tasks.register<Sync>("prepareNativeResources") {
  dependsOn(buildRustJvm)
  from(jvmNativeLibrary)
  into(generatedNativeResources.map {
    it.dir("META-INF/native/$jvmNativeOs/${jvmReleaseArch.get()}")
  })
}

val verifyReleaseNativeResources = tasks.register("verifyReleaseNativeResources") {
  group = "verification"
  description = "Verifies that all JVM native libraries are present in a release resource directory."
  inputs.dir(releaseNativeResources)
  doLast {
    val root = releaseNativeResources.orNull?.asFile
      ?: error("Set -PkestaraNativeResources to the assembled native resource directory")
    val missing = requiredReleaseNativeLibraries.filterNot { root.resolve(it).isFile }
    check(missing.isEmpty()) {
      "Missing release native libraries:\n${missing.joinToString("\n")}"
    }
  }
}

tasks.named<ProcessResources>("jvmProcessResources") {
  if (releaseNativeResources.isPresent) {
    dependsOn(verifyReleaseNativeResources)
    from(releaseNativeResources)
  } else {
    dependsOn(prepareNativeResources)
    from(generatedNativeResources)
  }
}

val cargoFmt = tasks.register<Exec>("cargoFmt") {
  group = "verification"
  workingDir(layout.projectDirectory.dir("native"))
  commandLine("cargo", "fmt", "--", "--check")
}

val cargoClippy = tasks.register<Exec>("cargoClippy") {
  group = "verification"
  workingDir(layout.projectDirectory.dir("native"))
  commandLine("cargo", "clippy", "--all-targets", "--locked", "--", "-D", "warnings")
}

val cargoTest = tasks.register<Exec>("cargoTest") {
  group = "verification"
  workingDir(layout.projectDirectory.dir("native"))
  commandLine("cargo", "test", "--locked")
}

tasks.named("check") {
  dependsOn(cargoFmt, cargoClippy, cargoTest)
}

tasks.withType<AbstractTestTask>().configureEach {
  testLogging {
    events(TestLogEvent.FAILED)
    exceptionFormat = TestExceptionFormat.FULL
    showCauses = true
    showExceptions = true
    showStackTraces = true
  }
}

tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  from(layout.projectDirectory.file("LICENSE")) { into("META-INF") }
  from(layout.projectDirectory.file("LICENSE-APACHE")) { into("META-INF") }
  from(layout.projectDirectory.file("LICENSE-MIT")) { into("META-INF") }
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()
  configure(KotlinMultiplatform(sourcesJar = SourcesJar.Sources()))
  coordinates(project.group.toString(), "kestara-webrtc", project.version.toString())

  pom {
    name = "Kestara WebRTC"
    description = "A Kotlin Multiplatform WebRTC DataChannel library powered by Rust."
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
  }
}
