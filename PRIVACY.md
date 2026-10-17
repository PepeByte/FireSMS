# Privacy

FireSMS is designed for local-first processing, but it necessarily handles sensitive financial information.

## Data FireSMS can access

Depending on your configuration and Android permissions, FireSMS may access or store:

- SMS sender names/numbers for messages received on the device,
- SMS bodies for messages that match configured sender rules or need attention,
- parsed transaction details,
- Firefly III server URL,
- Firefly III personal access token,
- local parser rules and title-mapping rules,
- retry queue payloads,
- exported backup files.

## What stays on device

Parser rules, local history, retry queue entries, preferences, and backups are managed by the app on your device unless you explicitly export or share them.

FireSMS ignores SMS senders that do not match any enabled sender parser rule.

## What is sent to Firefly III

When a message is successfully parsed, FireSMS sends transaction data to the Firefly III API configured by you. Sent fields may include:

- transaction type,
- date,
- amount,
- description,
- source account or source account ID,
- destination account,
- category,
- budget,
- notes,
- external ID derived from the SMS.

## Transport security

FireSMS only sends financial data and your Firefly III access token to the server address you
configure. These are not sent to the project's maintainers, GitHub, or any other third party.

Because Firefly III is self-hosted, FireSMS accepts both HTTPS and plain HTTP server addresses, and
trusts certificate authorities you install on the device. Plain HTTP means the token and transaction
data are not encrypted on the wire; anyone who can observe that network path could read or modify
them. Prefer HTTPS, especially over untrusted Wi-Fi or the public internet. Use plain HTTP only on a
network you trust, such as a LAN or a private VPN/tailnet.

## Update checks

FireSMS automatically checks the public `PepeByte/FireSMS` GitHub releases API for a newer stable
version approximately once a day when connected. You can also check manually in Settings.
These unauthenticated HTTPS requests send no SMS messages, transaction data, Firefly server address,
or access token. GitHub receives normal connection metadata, such as your IP address and the
FireSMS update-checker User-Agent. The app stores release information and the last successful check
on your device. Opening a release page uses your browser; downloading and installing an APK are
user-initiated, never automatic.

## Backups

Backup files are JSON and are not encrypted by FireSMS. They may include:

- Firefly III access token,
- Firefly III URL,
- SMS messages,
- transaction history,
- parser rules,
- title-mapping rules,
- pending retry payloads.

Store backups securely. Do not upload them to public issue trackers or pull requests.

## AI rule generation

FireSMS includes instructions that can be copied to an external AI service to generate parser-rule JSON. SMS messages can contain private financial information. Redact or replace personal values before sending examples to any external service.

## Removing local data

FireSMS includes local history retention settings and backup/restore features. History cleanup only removes local activity; it does not delete transactions from Firefly III.

## Contributors

Public examples, tests, screenshots, and documentation must use fake data. See [CONTRIBUTING.md](CONTRIBUTING.md).
