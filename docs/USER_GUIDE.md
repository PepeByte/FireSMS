# User Guide

This guide explains how to set up and use FireSMS.

## What FireSMS does

FireSMS listens for bank SMS messages that match your configured rules, extracts transaction details, and creates matching transactions in your Firefly III server.

It is not a bank app and does not connect to your bank directly. It only works with SMS messages received on your Android device.

## Requirements

- Android 8.0/API 26 or newer.
- SMS permission.
- A Firefly III server you control or trust.
- A Firefly III personal access token.
- At least one parser rule for your bank's SMS format.

## First setup

1. Open FireSMS.
2. Complete onboarding.
3. Grant SMS access when prompted.
4. Optional: grant notification permission so FireSMS can notify you when a transaction is created or an app update is available.
5. Enter your Firefly III server URL, for example:

   ```text
   https://firefly.example.com
   ```

   Use the address you open in a browser. Do not add `/api`. FireSMS also tolerates a trailing
   slash, a missing `http://`/`https://`, and a pasted `/api` suffix.

   If Firefly III is served under a subpath, include it:

   ```text
   https://example.com/firefly
   ```

   Plain HTTP and HTTPS with a private-CA certificate (for example self-hosted Caddy, Traefik, or
   mkcert) are both supported. For a private CA, install that certificate authority as a trusted
   certificate on the Android device first; FireSMS trusts certificates installed that way.

6. Enter your Firefly III personal access token. Pasting it with a trailing newline or spaces is safe.
7. Use **Save and test** to verify the connection.

## Creating a Firefly III token

In Firefly III, create a personal access token from your user/profile settings. Copy it into FireSMS settings.

Keep this token private. Anyone with the token may be able to access your Firefly III data according to the token's permissions.

## Creating parser rules

Go to **Automation** and create a parser rule.

A rule needs:

- a name,
- a sender regex that matches your bank SMS sender,
- a body regex that extracts amount and other details,
- transaction type,
- account mapping where needed.

See [PARSER_RULES.md](PARSER_RULES.md) for examples.

## Using AI rule import

The rule editor can copy AI generator instructions. You can paste those instructions and a sanitized SMS example into an AI tool, then paste the generated JSON back into FireSMS.

Before sharing an SMS with any external service, redact:

- account/card numbers,
- balances,
- transaction IDs,
- phone numbers,
- names,
- real merchants if private.

Review imported rules before saving them.

## Needs attention

Messages that FireSMS receives but cannot parse may appear in **Needs attention**. From there you can:

- create a new parser rule from the message,
- retry processing after adding or fixing a rule,
- delete the local unparsed message.

Deleting a message from FireSMS does not delete anything from Firefly III.

## Editing transactions

When FireSMS creates a transaction, it can show a notification. Use the notification or activity list to open the transaction editor.

The editor lets you adjust supported Firefly III transaction fields. Changes are sent back to Firefly III.

## Choosing accounts in a rule

The **Accounts** section of a parser rule follows the transaction type: **Paid from / Paid to** for withdrawals, **Received into / Received from** for deposits, and **Moved from / Moved to** for transfers. Pick your account from the list; the other account is optional for withdrawals and deposits. A check under each field shows whether the name exists in Firefly III, and the line **In Firefly: … → …** shows exactly which way the money will be recorded.

## Automatic categorization

Under **Automation**, title-mapping rules assign Firefly III categories and budgets when a transaction description contains the rule's text (case-insensitive). They are applied when a new SMS transaction is sent to Firefly III, and again when you save an edited transaction whose category and budget are both blank.

Rules are evaluated by priority. Use specific patterns before broad patterns.

## Retry behavior

If Firefly III is temporarily unavailable or the network fails, FireSMS queues the transaction and retries later.

You can configure retry network preference in **Settings**:

- Wi-Fi or mobile data,
- Wi-Fi only.

Some Firefly III errors, such as invalid transaction data, may not be retryable until you fix the rule or configuration.

On **Activity**, use **Retry failed (N)** and confirm to retry all failed transactions, regardless of the selected filter. This reparses their original SMS using your current rules and settings. Synced transactions, messages still processing, and unparsed messages are excluded. You can still retry a single failed transaction from its details.

Bulk retries run in the background, follow your network preference, and show queued/progress status on Activity. Completed attempts are not replayed if the batch is interrupted; an interrupted in-flight attempt may run again. Temporary failures can return to the automatic retry queue. Changing the network preference updates unfinished retries; a request already in flight may finish first. Before retrying an uncertain failure (for example, a lost server response), check Firefly III to avoid creating a duplicate.

## Backup and restore

Settings includes backup and restore.

Backups include local rules, mappings, history, retry queue, and settings. They may also include your Firefly III token and SMS messages.

Backup files are not encrypted by FireSMS. Store them securely.

## History retention

FireSMS can automatically delete old local activity. This only affects FireSMS local history. It does not delete Firefly III transactions.

## App updates

FireSMS checks the [GitHub releases](https://github.com/PepeByte/FireSMS/releases) for a newer stable
version approximately once a day when connected. Pre-releases and drafts are ignored. Android may
delay checks because of battery or network restrictions.

- Enable notifications to receive an update alert. Each new version is announced once; you can
  manage the **App updates** notification channel in Android settings.
- Open **Settings → App updates → Check for updates** to check immediately. This works even without
  notification permission and shows the last successful check or a connection/rate-limit error.
- When an update is available, tap **View release on GitHub** (or the notification) to review it and
  download its APK in your browser. Install only the project's official release APK, signed with the
  same key as your installed app. Android may require permission to install apps from your browser
  and will ask you to confirm installation.

FireSMS never downloads or installs updates automatically. GitHub update checks are separate from
the Firefly III transaction-sync network preference and may use Wi-Fi or mobile data. They send no
financial data or Firefly III credentials; see [Privacy](../PRIVACY.md#update-checks).

## Troubleshooting

### No transactions are created

Check:

- SMS permission is granted.
- Firefly III URL and token are correct.
- Connection test succeeds.
- At least one enabled parser rule matches the SMS sender.
- The parser rule body pattern matches the SMS body.

### Messages are ignored

FireSMS ignores senders that do not match enabled sender rules. Check the sender pattern in your parser rule.

### Transactions fail repeatedly

Open the activity/error details and check the Firefly III error. The parser may be producing invalid account names, dates, amounts, or transaction types.

### Backup warning

Treat exported backup files like sensitive financial records.
