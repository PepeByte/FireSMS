# Security Policy

FireSMS handles SMS messages, financial transaction data, and Firefly III access tokens. Please report security issues responsibly.

## Reporting a vulnerability

Do not open a public issue for security vulnerabilities or sensitive-data exposure.

Until a dedicated security contact is published, please use GitHub's private vulnerability reporting feature if available for this repository, or contact the project maintainer privately.

When reporting, include:

- affected version or commit,
- description of the issue,
- steps to reproduce,
- potential impact,
- any suggested mitigation.

Do not include real SMS messages, real tokens, or private financial information unless a secure private channel has been agreed.

## Sensitive areas

Please be extra careful when changing:

- SMS receiving and filtering,
- parser-rule import and regex handling,
- Firefly III API requests,
- DataStore token storage,
- backup and restore,
- retry queue payloads,
- transaction editing.

## Supported versions

This project is early-stage. Security fixes are expected to target the current `main` branch unless release branches are introduced later.
