# Repository instructions

## Commits

Use Conventional Commit format: `<type>(<scope>): <description>`.

Do not bypass commit hooks. Do not create a branch unless the user requests one.

## Kotlin

Use modern Kotlin Multiplatform APIs. Put shared behavior in `commonMain`. Keep the public API independent from JNI, cinterop, and Rust implementation types.

## Rust

Keep the JNI and C ABI boundaries small. Do not let a panic cross either native boundary. Do not invoke application code on native protocol threads.

## Verification

Run `./gradlew check` before each commit.
