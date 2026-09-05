# Contributing to FireSMS

Thank you for your interest in contributing to FireSMS. This project is open source and welcomes bug reports, documentation improvements, parser-rule examples, tests, and code changes.

## Before you contribute

FireSMS processes sensitive financial data. Do not share real private data in public project spaces.

Never include:

- real SMS messages,
- bank account numbers,
- card numbers,
- phone numbers,
- Firefly III personal access tokens,
- private Firefly III server URLs,
- exported FireSMS backup files,
- screenshots containing financial or personal information.

Use realistic but fake examples instead.

## Development setup

Read [docs/BUILDING.md](docs/BUILDING.md) for Android SDK, JDK, Gradle, and build instructions.

Useful commands:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

## Project structure

```text
app/src/main/java/com/firesms/app/
  data/       Room, DataStore, backup, Firefly API models
  domain/     parser, rule import, domain models
  service/    SMS receiver and foreground processing service
  ui/         Jetpack Compose screens, components, navigation, theme
  worker/     retry and history cleanup workers
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for more details.

## Pull request checklist

Before opening a pull request, please check:

- [ ] The change does not include sensitive real-world SMS or Firefly data.
- [ ] Unit tests pass with `./gradlew testDebugUnitTest`.
- [ ] Android lint passes with `./gradlew lintDebug` or any lint findings are explained.
- [ ] The debug build succeeds with `./gradlew assembleDebug`.
- [ ] Parser changes include tests or documented examples.
- [ ] Room schema changes include a database migration.
- [ ] User-facing behavior changes are documented in README or `docs/` where appropriate.

## Coding guidelines

- Prefer small, focused pull requests.
- Keep UI code in Compose screens/components and business logic in view models/domain/data layers.
- Keep parser behavior deterministic and testable.
- Use Kotlin coroutines carefully; avoid blocking the main thread.
- Treat SMS processing, backup/restore, retry, and token handling as sensitive areas.
- Do not commit generated build output, local SDK configuration, keystores, or secrets.

## Parser-rule contributions

Parser-rule examples are useful, but they must be sanitized.

Good example:

```text
A/C XX1234 debited by USD 12.34 at EXAMPLE STORE on 2025-01-31 Ref ABC123.
```

Bad example:

```text
A real bank SMS with a real account number, merchant, balance, or reference ID.
```

See [docs/PARSER_RULES.md](docs/PARSER_RULES.md) for the rule format.

## Issue reports

When reporting a bug, include:

- FireSMS version or commit,
- Android version and device model if relevant,
- expected behavior,
- actual behavior,
- sanitized logs or screenshots,
- steps to reproduce.

If the issue involves security or sensitive data exposure, do not open a public issue. Follow [SECURITY.md](SECURITY.md).
