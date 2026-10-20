# Changelog

All notable changes to FireSMS will be documented in this file.

## Unreleased

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
