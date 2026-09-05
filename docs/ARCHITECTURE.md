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
```

## Application startup

`FireSmsApp` creates shared app-level dependencies:

- `AppDatabase`
- `PreferencesManager`
- history cleanup scheduling

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

`FireflyApi` uses Ktor and a bearer token. It supports:

- testing connection with `/api/v1/about`,
- creating transactions,
- updating transactions,
- fetching transaction details,
- account/category/budget/autocomplete lookups.

Client errors are represented as `FireflyError`. Network/server failures are represented as `FireflyUnavailable` and may be retried.

## Retry queue

Failed transaction creates caused by network/server unavailability are stored in `pending_transactions`. `RetryWorker` later retries due items using WorkManager with network constraints based on the user's sync preference:

- any network,
- Wi-Fi only.

Retries use exponential backoff and update the related SMS log.

## Local storage

FireSMS uses:

- Room database: SMS logs, pending transactions, parser rules, title mappings,
- DataStore preferences: Firefly URL/token, onboarding, sync policy, history retention.

Current Room database version: `6`.

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
