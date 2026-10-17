# Architecture

FireSMS is a single-module Android app written in Kotlin with Jetpack Compose.

## High-level flow

```text
SMS broadcast
  -> SmsReceiver
  -> sender rule pre-filter
  -> SmsProcessingService
  -> SmsParser
  -> FireflyApi
  -> Room log / retry queue
  -> WorkManager retry if needed
```

## Main packages

```text
com.firesms.app
  FireSmsApp.kt             Application setup
  MainActivity.kt           Compose entry point

com.firesms.app.data
  backup/                   Export and restore JSON backups
  local/                    Room database, DAOs, DataStore preferences
  remote/                   Firefly III API client and DTOs
  update/                   GitHub release checks and device-local update metadata

com.firesms.app.domain
  model/                    Parsed transaction models
  parser/                   SMS parser and title mapping resolver
  rules/                    Importable parser-rule JSON handling

com.firesms.app.service
  SmsReceiver.kt            Receives SMS broadcasts
  SmsProcessingService.kt   Foreground processing service

com.firesms.app.ui
  components/               Shared Compose components
  navigation/               Navigation routes and graph
  screens/                  Compose screens
  theme/                    Material theme
  viewmodels/               Screen state and app logic

com.firesms.app.worker
  RetryWorker.kt            Retries queued Firefly submissions
  HistoryCleanupWorker.kt   Local history retention cleanup
  AppUpdateWorker.kt        Daily stable-release checks and update notifications
```

## Application startup

`FireSmsApp` creates shared app-level dependencies:

- `AppDatabase`
- `PreferencesManager`
- `UpdateRepository` and app-update notification channel
- history cleanup and daily app-update scheduling

`MainActivity` initializes Compose, creates the nav controller, and starts at onboarding. The onboarding screen auto-skips once completed.

## SMS receiving

`SmsReceiver` listens for Android `SMS_RECEIVED` broadcasts. For each SMS part/message, it:

1. extracts sender, body, and received timestamp,
2. loads enabled parser rules,
3. checks whether any enabled sender regex matches,
4. starts `SmsProcessingService` only for matching senders.

This sender pre-filter reduces unnecessary storage and processing.

## SMS processing

`SmsProcessingService` runs as a foreground service. It:

1. deduplicates new SMS messages using a SHA-256 hash,
2. inserts or reuses an `SmsLog`,
3. parses the message with `SmsParser`,
4. builds a Firefly III transaction request,
5. sends the request through `FireflyApi`,
6. updates local status to `success`, `failed`, or `unparsed`,
7. queues retriable failures in `pending_transactions`.

The service uses `DataMaintenanceLock` to avoid conflicts with destructive restore/cleanup operations inside the app process.

## Parser rules

`SmsParser` loads enabled rules ordered by priority. A rule has:

- sender regex,
- body regex,
- amount template,
- description mapping,
- source/destination account mapping,
- transaction type,
- optional date and remarks extraction.

See [PARSER_RULES.md](PARSER_RULES.md).

## Firefly III integration

`FireflyApi` uses Ktor's OkHttp engine and a bearer token. It supports:

- testing connection with `/api/v1/about`,
- creating transactions,
- updating transactions,
- fetching transaction details,
- account/category/budget/autocomplete lookups.

HTTP behaviour:

- A single `HttpClient` is shared for the process lifetime, so connections and TLS sessions are
  reused across the several calls a screen makes. `FireflyApi.close()` is a no-op kept for call sites.
- Timeouts are 45 s request and read, with a 15 s connect timeout. `socketTimeoutMillis` must be set
  explicitly: Ktor's OkHttp engine only overrides OkHttp's default read timeout when it is present.
  Without it, requests were silently capped at OkHttp's 10 s read timeout, so a slow but healthy
  Firefly III failed with `SocketTimeoutException`.
- The server address is normalised (`apiUrl`) and accepts a trailing slash or newline, a missing
  scheme, and a pasted `/api` or `/api/v1` suffix. Access tokens are trimmed.
- `testConnection` requires a successful JSON response under an `/api/` path. Firefly redirects
  unauthenticated requests to `/login`, and following that redirect previously made a bad token
  look like a successful connection.

Client errors are represented as `FireflyError`. Network/server failures are represented as
`FireflyUnavailable` and may be retried. Transport failures are converted to user-actionable text
by `networkErrorMessage` rather than surfaced as raw Ktor/OkHttp exceptions.

### Transport restrictions

Self-hosted Firefly III instances are commonly reachable only over plain HTTP on a LAN or tailnet,
and often use HTTPS with a certificate from a private CA. `res/xml/network_security_config.xml`
permits cleartext traffic and trusts user-installed CA certificates so those setups work. The app
loads no third-party or remote web content, so this does not widen exposure for unrelated traffic.
Both lint warnings for this configuration are acknowledged deliberately in that file.

## Retry queue

Failed transaction creates caused by network/server unavailability are stored in `pending_transactions`. `RetryWorker` later retries due items using WorkManager with network constraints based on the user's sync preference:

- any network,
- Wi-Fi only.

Retries use exponential backoff and update the related SMS log.

## App updates

`GitHubReleaseClient` uses a separate unauthenticated Ktor client to query the public
`PepeByte/FireSMS` `/releases/latest` API over HTTPS. It rejects drafts/prereleases, compares stable
numeric versions (not lexicographic strings), never offers downgrades, and derives trusted release
page URLs from validated tags. It never receives Firefly credentials. Missing releases are handled
as no update; rate limits, transport failures, and malformed responses are errors.

`UpdateRepository` serializes manual/background checks and notification cleanup. A separate
`app_updates` DataStore preserves release availability, successful-check time, and the last notified
version; this device-local metadata is not part of exported financial backups. Failed checks leave
the previous successful result intact, and cached offers are hidden after upgrading.

`AppUpdateWorker` is unique daily connected-network work using `KEEP` to preserve its cadence. It
can run immediately after first scheduling; Android may defer it. Failures use bounded hourly
exponential retries before waiting for the next period. An enabled notification is posted once per
new version; denied/disabled notifications are not marked delivered. Settings supports manual
checks and cached availability even when notifications are disabled. Release links open externally;
no APK download or installation is performed by the app.

## Local storage

FireSMS uses:

- Room database: SMS logs, pending transactions, parser rules, title mappings,
- DataStore preferences: Firefly URL/token, onboarding, sync policy, history retention.

Current Room database version: `7`.

## Backup and restore

`BackupRepository` exports a JSON backup containing preferences, logs, retry queue, parser rules, and title mappings. Backup files are not encrypted and may include sensitive SMS data and Firefly tokens.

Restore clears existing local records and preferences before importing the backup data.

## UI

The UI is built with Jetpack Compose and Material 3. Main screens include:

- Onboarding,
- Activity/Home,
- Automation/Rules,
- Rule editor,
- Needs attention/Unparsed,
- Edit transaction,
- Settings.
