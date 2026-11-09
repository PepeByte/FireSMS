# Changelog

All notable changes to FireSMS will be documented in this file.

## Unreleased

## 1.1.0 — 2026-11-10

### Activity
- Transactions that will retry on their own now show as **Waiting to sync** instead of Failed; Failed is kept for items that can't be retried or whose automatic retries ran out.
- The health panel lists unparsed, failed and waiting items separately, and its colours now distinguish "all good" (green) from "needs attention" (amber).
- New **Unmodified** and **Modified** filters, counts on every filter, and search by description, sender or amount.
- The list loads a page at a time with database indexes, so it stays fast with long history.
- Amounts are larger, signed (− / +) and green for deposits; dates include the year when needed and follow the 12/24-hour setting.
- Transaction details open in a bottom sheet with Edit, Retry, Copy and Delete.
- **Process sample SMS** warns that it creates a real transaction and reports the outcome.

### Notifications
- One notification per SMS (grouped), with the amount.
- New **Needs attention** notifications for unparsed and failed messages that open the right screen.

### Needs attention
- **Try all again**, a progress spinner per message, readable results and a visible Delete button.

### Rules
- Rule testing explains why a message didn't match and shows every captured value and the parsed date.
- A live preview re-runs the rule against the sample SMS as you type.
- Rules created from an SMS match the bank's sender with any operator prefix (AD-, VM-, JD-…).
- Errors are shown on the field at fault, with plain-language regex messages and help for named groups.
- Importing rule JSON reports the error position and can be undone.
- Rules with equal priority now run in name order.

### Edit transaction
- Splits show a live "allocated / left" total next to Save, and new splits get a sensible amount.
- Group title is required for split transactions.
- Removing a split can be undone; leaving with unsaved edits asks first.
- The form opens as soon as the transaction loads, with Firefly suggestions loaded in the background and cached.

### Settings and setup
- Server addresses default to https, with a warning for http. Existing scheme-less addresses keep using http.
- The connection test explains failures (rejected token, wrong URL, web page instead of API, unreachable, certificate).
- A single **Save and test** button, URL and password keyboards, and a show/hide toggle for the access token.
- A blocked SMS permission offers **Open app settings**; permission status refreshes when you return to the app.
- New battery-optimisation step, and **Use wallpaper colours** on Android 12+.
- **Wi-Fi only** sync now applies to new transactions too, not just retries.
- Choosing a shorter history retention asks before deleting; restore reports what it replaced and keeps you out of onboarding.

### Across the app
- Back asks before discarding unsaved changes on every edit screen.
- A complete colour scheme (no more purple-tinted dialogs), one consistent card style and a scroll-aware top bar.
- Splash screen and correct light/dark launch window.
- Fixed: the Automation page crashed on devices when any rule existed.
- Debug builds install as a separate **FireSMS Debug** app (`com.firesms.app.debug`) with a grey icon and no update checks.
- Release APKs are smaller (unused resources are removed).

## 1.0.4 — 2026-11-08

- Fixed: SMS processing and retries check for matching transactions already in Firefly III and reuse them instead of creating duplicates, including after a request times out following a successful import.
- Added duplicate-transaction matching regression tests.

## 1.0.3 — 2026-10-21

- Fixed: backups wait for SMS/retry processing to finish before capturing settings and records.
- Fixed: exported dead-letter retry records with unreadable payloads can be restored without making them runnable.
- Fixed: restore rolls back database changes if settings cannot be written, and completes an in-progress replacement despite navigation cancellation.
- Added populated backup round-trip, validation, and restore-failure regression tests.
- Added daily checks for newer stable GitHub releases, with one notification per version and no automatic downloads or installation.
- Added **Settings → App updates** with manual checking, cached update availability, last successful check time, and a link to the official release page.
- Added **Ignore this update** with a persistent per-version skip, notification dismissal, and an undo option; newer releases are still offered.
- Added **Ignore this update** directly to update notifications; the action works offline and can be undone in Settings.
- Documented update-check privacy and manual APK installation.

## 1.0.2 — 2026-10-17

- Added **Retry failed (N)** on Activity to retry all failed transactions with the current rules and settings after confirmation.
- Bulk retries run in the background, respect the network preference, and display queue/progress status without replaying completed attempts after interruption.
- Added an automated release workflow that tests, lints, builds, and signs the APK, then publishes it with a SHA-256 checksum on GitHub.
- Android CI now runs on pushes to all branches.

## 1.0.1

- Initial open-source documentation.
- Android CI workflow for checks on pull requests and main-branch pushes.
- Fixed: long (multi-part) SMS messages are joined before parsing instead of being processed as separate fragments.
- Fixed: automatic categorization rules are now applied to newly created transactions, not only on edit.
- Fixed: a rule whose template references a missing group no longer stops later rules from matching.
- Fixed: deposit rules send your account as the destination, as Firefly III requires.
- Fixed: invalid amounts are no longer sent to Firefly III; the rule simply does not match.
- Fixed: dates with upper-case month names and date-only formats now parse.
- Fixed: the default "Imported from FireSMS" note is now actually sent to Firefly III.
- Fixed: queued retries stop after a non-retryable error instead of retrying indefinitely, and wake up when the next retry is due.
- Fixed: tapping a "Transaction added" notification opens the transaction instead of the home screen.
- Fixed: the transaction date picker keeps the transaction's date and time-zone offset.
- Rule editor accounts are labelled by transaction type (Paid from / Paid to, Received into / Received from, Moved from / Moved to), show a "source → destination" preview, and both fields search Firefly III accounts with a found/not-found check. The other account's Firefly ID is now stored (database version 7).
- Firefly account lists are fetched across all pages.
- The rule editor now validates templates, groups, and date formats with the same checks as the JSON importer, and refuses to overwrite an existing rule ID.
- Fixed: the HTTP client no longer used OkHttp's 10-second default read timeout. Ktor only applies `socketTimeoutMillis` (never set) to that timeout, so a slow but healthy Firefly III answered too late and the app failed with "Socket timeout has expired". Requests now allow 45 seconds while connecting within 15.
- Fixed: plain-HTTP and self-signed/private-CA Firefly III servers were rejected by Android ("CLEARTEXT communication ... not permitted" and "PKIX path building failed"). A network security config now permits both, which self-hosted LAN and tailnet setups need.
- Fixed: one HTTP client is reused for the process instead of building a new engine and thread pool per request, so screens that make several calls no longer pay a fresh TCP/TLS handshake each time.
- Fixed: "Test connection" reported success for an invalid token, because Firefly's 302 redirect to `/login` was followed and its 200 page counted as success.
- Fixed: transport failures now show an actionable message (unreachable host, timeout, untrusted certificate, whitespace in the token) instead of raw exceptions such as `SocketTimeoutException`.
- Fixed: a pasted server URL with a trailing newline, trailing slash, missing scheme, or an extra `/api`/`/api/v1` suffix is normalised instead of failing to parse. Access tokens are trimmed before use.
