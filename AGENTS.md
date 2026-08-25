# Repository instructions

## Commits

Use Conventional Commit format: `<type>(<scope>): <description>`.

Do not bypass commit hooks. Do not create a branch unless the user requests one.

## Java

Use Java 17 language and API features. Keep the public API independent from JNI and Rust implementation types.

## Rust

Keep the JNI boundary small. Do not let a panic cross the native boundary. Do not invoke application callbacks on native protocol threads.

## Verification

Run `./gradlew check` before each commit.
