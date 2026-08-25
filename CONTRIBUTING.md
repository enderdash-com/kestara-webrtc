# Contributing

Alloy WebRTC accepts focused bug reports and pull requests.

## Prepare the repository

Install Java 17 and Rust 1.85, or newer releases. Then clone the repository.

## Verify a change

Run all checks before you open a pull request:

```bash
./gradlew check
```

This command compiles Java and Rust code. It also runs unit tests, Rustfmt, and Clippy.

## Commit messages

Use Conventional Commit format:

```text
<type>(<scope>): <description>
```

Use an imperative and concise description. Add a commit body when the reason is not clear from the description.

## Pull requests

Keep each pull request focused on one change. Describe the behavior, the reason for the change, and the verification that you completed.
