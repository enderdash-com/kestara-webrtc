package com.enderdash.kestara.webrtc.internal

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

internal object NativeLibraryLoader {
  @Volatile private var loaded = false

  @Synchronized
  fun load() {
    if (loaded) return
    val platform = Platform.current()
    val resourcePath = "/META-INF/native/${platform.operatingSystem}/${platform.architecture}/${platform.libraryName}"
    val library = NativeLibraryLoader::class.java.getResourceAsStream(resourcePath)
      ?: throw UnsatisfiedLinkError(
        "Kestara WebRTC does not include a native library for ${platform.displayName} ${platform.architecture}",
      )
    library.use {
      val directory = Files.createTempDirectory("kestara-webrtc-")
      val extractedLibrary = directory.resolve(platform.libraryName)
      Files.copy(it, extractedLibrary, StandardCopyOption.REPLACE_EXISTING)
      extractedLibrary.toFile().deleteOnExit()
      directory.toFile().deleteOnExit()
      System.load(extractedLibrary.toAbsolutePath().toString())
      loaded = true
    }
  }

  private data class Platform(
    val operatingSystem: String,
    val architecture: String,
    val libraryName: String,
    val displayName: String,
  ) {
    companion object {
      fun current(): Platform {
        val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val architecture = when (System.getProperty("os.arch", "").lowercase(Locale.ROOT)) {
          "amd64", "x86_64" -> "x86_64"
          "aarch64", "arm64" -> "aarch64"
          else -> throw UnsupportedOperationException("Unsupported architecture")
        }
        return when {
          osName.startsWith("windows") -> Platform(
            "windows", architecture, "kestara_webrtc_native.dll", osName,
          )
          osName.startsWith("mac") -> Platform(
            "macos", architecture, "libkestara_webrtc_native.dylib", osName,
          )
          osName.startsWith("linux") -> Platform(
            "linux", architecture, "libkestara_webrtc_native.so", osName,
          )
          else -> throw UnsupportedOperationException("Unsupported operating system: $osName")
        }
      }
    }
  }
}
