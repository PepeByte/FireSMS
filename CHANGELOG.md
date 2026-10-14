# Changelog

All notable changes to FireSMS will be documented in this file.

This project currently does not have a formal release history.

## Unreleased

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
- The rule editor now validates templates, groups, and date formats with the same checks as the JSON importer, and refuses to overwrite an existing rule ID.
