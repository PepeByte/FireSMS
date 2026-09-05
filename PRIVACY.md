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
