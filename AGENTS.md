# Repository instructions

## Required Lefthook setup

- Use Lefthook as the default Git hook manager for this repository.
- Before other project work, install the dependencies needed by the hooks.
- From the repository root, run `lefthook install`.
- Run `lefthook validate` and `lefthook check-install` successfully.
- Make sure that Git's effective hook directory contains executable Lefthook hooks, including every configured commit hook.
- If hooks are missing, outdated, disabled, or fail installation checks, refuse all other project work.
- While blocked, only perform the setup and diagnostics needed to install and verify the hooks.
- Never bypass hooks with `--no-verify`, `-n`, environment variables, or configuration changes.
- Let the hooks finish and fix failures before committing or pushing.

## Commits

Use Conventional Commit format: `<type>(<scope>): <description>`.

Do not bypass commit hooks. Do not create a branch unless the user requests one.

## Kotlin

Use modern Kotlin Multiplatform APIs. Put shared behavior in `commonMain`. Keep the public API independent from JNI, cinterop, and Rust implementation types.

## Rust

Keep the JNI and C ABI boundaries small. Do not let a panic cross either native boundary. Do not invoke application code on native protocol threads.

## Verification

Run `./gradlew check` before each commit.
