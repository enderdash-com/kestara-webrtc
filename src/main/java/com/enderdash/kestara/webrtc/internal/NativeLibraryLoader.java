package com.enderdash.kestara.webrtc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class NativeLibraryLoader {
    private static boolean loaded;

    private NativeLibraryLoader() {}

    static synchronized void load() {
        if (loaded) {
            return;
        }

        Platform platform = Platform.current();
        String resourcePath = "/META-INF/native/"
                + platform.operatingSystem()
                + "/"
                + platform.architecture()
                + "/"
                + platform.libraryName();

        try (InputStream library = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (library == null) {
                throw new UnsatisfiedLinkError(
                        "Kestara WebRTC does not include a native library for " + platform.platformName());
            }

            Path directory = Files.createTempDirectory("kestara-webrtc-");
            Path extractedLibrary = directory.resolve(platform.libraryName());
            Files.copy(library, extractedLibrary, StandardCopyOption.REPLACE_EXISTING);
            extractedLibrary.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            System.load(extractedLibrary.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record Platform(
            String operatingSystem,
            String architecture,
            String libraryName,
            String displayName) {
        private static Platform current() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String architecture = normalizeArchitecture(System.getProperty("os.arch", ""));

            if (osName.startsWith("windows")) {
                return new Platform("windows", architecture, "kestara_webrtc_native.dll", osName);
            }
            if (osName.startsWith("mac")) {
                return new Platform(
                        "macos", architecture, "libkestara_webrtc_native.dylib", osName);
            }
            if (osName.startsWith("linux")) {
                return new Platform("linux", architecture, "libkestara_webrtc_native.so", osName);
            }
            throw new UnsupportedOperationException("Unsupported operating system: " + osName);
        }

        private static String normalizeArchitecture(String architecture) {
            return switch (architecture.toLowerCase(Locale.ROOT)) {
                case "amd64", "x86_64" -> "x86_64";
                case "aarch64", "arm64" -> "aarch64";
                default -> throw new UnsupportedOperationException(
                        "Unsupported architecture: " + architecture);
            };
        }

        private String platformName() {
            return displayName + " " + architecture;
        }
    }
}
