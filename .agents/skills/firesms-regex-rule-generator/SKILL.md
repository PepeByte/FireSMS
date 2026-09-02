---
name: firesms-regex-rule-generator
description: Generate copy/paste-ready FireSMS parser-rule JSON from a user's transaction SMS body. Use when the user asks to create, test, or fix an importable FireSMS body regex and its amount, description, type, date, or remarks mappings.
---

# FireSMS Regex Rule Generator

Convert a user-provided transaction SMS body into a JSON object that can be pasted into FireSMS using **Load rule JSON**.

## Interaction workflow

1. Ask the user to paste the complete SMS body if they have not provided it.
2. Ask a concise clarification only when a required value cannot be safely inferred, such as an ambiguous transaction type or unclear description boundary.
3. Construct and mentally test the rule against the supplied SMS.
4. Serialize the rule as strict JSON and verify that a standard JSON parser would accept it.
5. In the final answer, output only the raw JSON object defined below.

The final answer must not contain an explanation, expected parse, Markdown code fence, or text before or after the JSON. This lets the user copy the entire answer directly into FireSMS.

## Exact output contract

Output exactly these nine keys, in this order:

```json
{
  "bodyPattern": "",
  "amountTemplate": "",
  "descriptionSource": "",
  "descriptionValue": "",
  "type": "",
  "datePattern": "",
  "dateFormat": "",
  "remarksPattern": "",
  "remarksTemplate": ""
}
```

Do not add fields such as `id`, `name`, `enabled`, `senderPattern`, `priority`, `sourceAccountKeyword`, `sourceAccountName`, `sourceAccountId`, or `destinationAccountName`. The user configures those fields in FireSMS.

All nine keys must be present. Use an empty string for an unused optional field. Do not use `null`.

## Non-negotiable JSON escaping rule

The output must be accepted by a standard JSON parser exactly as displayed.

For unavoidable regex backslashes, use the JSON Unicode escape `\u005C` as the preferred representation. It reliably becomes one regex backslash after JSON decoding without relying on doubled slashes that some LLMs collapse.

| Invalid final JSON | Preferred valid JSON | Decoded regex |
|---|---|---|
| `"bodyPattern": "credited\s+by"` | `"bodyPattern": "credited\u005Cs+by"` | `credited\s+by` |
| `"bodyPattern": "(?<amount>\d+)"` | `"bodyPattern": "(?<amount>\u005Cd+)"` | `(?<amount>\d+)` |
| `"bodyPattern": "NPR\.(?<amount>...)"` | `"bodyPattern": "NPR\u005C.(?<amount>...)"` | `NPR\.(?<amount>...)` |

A doubled backslash such as `\\s` is also valid JSON, but `\u005Cs` is preferred for generated output. Never place a raw one-backslash sequence such as `\s`, `\d`, `\w`, or `\.` directly in a JSON string.

Prefer backslash-free equivalents whenever possible:

- Prefer `[0-9]` over `\d`.
- Prefer `[.]` over `\.` for a literal dot.
- Prefer `[0-9,]+(?:[.][0-9]{1,2})?` for an amount.

Before answering, verify that every unavoidable regex backslash in `bodyPattern`, `datePattern`, and `remarksPattern` is written as `\u005C`. Then verify the complete response with a standard JSON parser.

## App parser contract

FireSMS parses rules using `app/src/main/java/com/firesms/app/domain/parser/SmsParser.kt`:

- `bodyPattern` is compiled as a Kotlin/Java regex with `DOT_MATCHES_ALL` and matched with `.find(body)`.
- `bodyPattern` must expose every named group referenced by a template.
- Templates use placeholders such as `{{amount}}`, `{{merchant}}`, and `{{date}}`.
- `amountTemplate` is required. If a placeholder does not resolve to a named group, the SMS will not parse.
- Extracted amounts are cleaned by removing everything except digits and dots.
- `descriptionSource` must be `template` or `regex_group`.
- `type` must be `withdrawal`, `deposit`, or `transfer`.
- `datePattern` may be `{{dateGroup}}` or a standalone regex.
- `dateFormat` is a Java `DateTimeFormatter` pattern used with `LocalDateTime.parse(..., Locale.ENGLISH)`.
- `remarksPattern` may be `{{groupName}}`, a standalone regex, or blank.
- `remarksTemplate` may use any named groups captured by `bodyPattern`.

## Regex compatibility

Generate Kotlin/Java-compatible regular expressions:

- Use named captures in the form `(?<amount>...)`.
- Named groups must begin with a letter and contain letters and digits only. Prefer camelCase, such as `txnId` or `fromAccount`.
- Never use underscores in group names.
- Escape literal regex metacharacters where needed.
- Use regex `\s+` for flexible spaces and line breaks, but write it as `\u005Cs+` in the final JSON string.
- Use bounded captures based on reliable delimiters.
- Use non-greedy captures such as `(?<remarks>.*?)` for free text between known delimiters.
- Avoid broad `.*` near an amount unless surrounding delimiters are reliable.
- Avoid lookbehind when a delimiter-based capture works.

These rules describe decoded regex behavior. In the final JSON response, represent regex backslashes with `\u005C`.

## Field guidance

### `bodyPattern`

Identify stable phrases around variable data and capture useful values as named groups.

Prefer fragments that minimize backslash escaping:

```text
(?<amount>[0-9,]+(?:[.][0-9]{1,2})?)
(?<account>#+[0-9]+)
(?<date>[0-9]{4}-[0-9]{2}-[0-9]{2}\u005Cs+[0-9]{2}:[0-9]{2}:[0-9]{2})
(?<merchant>[^:]+)
(?<txnId>[A-Za-z0-9]+)
(?<remarks>.*?)
```

The `\u005Cs+` shown above is ready for direct placement inside the final JSON string and decodes to regex `\s+`.

### `amountTemplate`

Capture the transaction amount as `amount` whenever possible and use:

```text
{{amount}}
```

The recommended amount group supports values such as `800`, `3,706.80`, and `3706.8`:

```text
(?<amount>[0-9,]+(?:[.][0-9]{1,2})?)
```

### `descriptionSource` and `descriptionValue`

Prefer:

```text
descriptionSource = template
```

Then build a useful title from groups or static text:

```text
{{merchant}}
{{merchant}} {{method}}
{{remarks}}
Bank transaction
```

Use `descriptionSource = regex_group` only when `descriptionValue` is exactly one group name without braces, for example:

```text
descriptionSource = regex_group
descriptionValue = merchant
```

Every placeholder used by a template must exist as a named group in `bodyPattern`.

### `type`

Infer the transaction type from the wording:

- `debited`, `withdrawn`, `paid`, `sent`, `purchase`, `spent` → `withdrawal`
- `credited`, `received`, `deposited`, `refund` → `deposit`
- clear movement between the user's own accounts → `transfer`

Ask the user if the wording is genuinely ambiguous.

### `datePattern` and `dateFormat`

Only populate both fields when the SMS contains a date and time that can be parsed as a `LocalDateTime`. Otherwise set both to an empty string.

Prefer capturing the complete value in `bodyPattern`, then referencing it:

```text
datePattern = {{date}}
```

Common Java date formats:

```text
2026-04-19 16:30:47 → yyyy-MM-dd HH:mm:ss
19/04/2026 16:30:47 → dd/MM/yyyy HH:mm:ss
19-Apr-2026 16:30 → dd-MMM-yyyy HH:mm
19 Apr 2026 04:30 PM → dd MMM yyyy hh:mm a
```

Date-only strings normally cannot be parsed by FireSMS and should leave both fields blank.

### `remarksPattern` and `remarksTemplate`

Use `remarksTemplate` to construct Firefly notes from named groups. It may refer directly to any group in `bodyPattern`:

```text
remarksPattern =
remarksTemplate = Ref: {{txnId}} | Account: {{account}}
```

When notes should be exactly one captured group, use:

```text
remarksPattern = {{remarks}}
remarksTemplate = {{remarks}}
```

Set both fields to empty strings when no useful remarks exist.

## Construction checklist

Before returning the JSON:

1. Confirm `bodyPattern` matches the supplied SMS with `.find()` semantics.
2. Confirm the amount is captured and `amountTemplate` resolves.
3. Confirm every template placeholder matches a named `bodyPattern` group.
4. Confirm named groups contain no underscores.
5. Confirm `descriptionSource` and `type` use allowed values.
6. Confirm date fields are either both usable or both empty.
7. Confirm optional fields use empty strings rather than `null`.
8. Confirm the final response contains no raw one-backslash JSON sequences such as `\s`, `\d`, `\w`, or `\.`. Represent unavoidable regex backslashes as `\u005C`.
9. Confirm a standard JSON parser would accept the response exactly as displayed.
10. Confirm the JSON contains exactly the nine supported keys.
11. Return only the raw JSON object.

## Example

Input SMS:

```text
Your account ##1234 was debited by USD 24.50 on 2026-04-19 16:30:47 by:Example Store:CARD:ABC123456
```

Final output:

```json
{
  "bodyPattern": "Your\u005Cs+account\u005Cs+(?<account>#+[0-9]+)\u005Cs+was\u005Cs+debited\u005Cs+by\u005Cs+USD\u005Cs+(?<amount>[0-9,]+(?:[.][0-9]{1,2})?)\u005Cs+on\u005Cs+(?<date>[0-9]{4}-[0-9]{2}-[0-9]{2}\u005Cs+[0-9]{2}:[0-9]{2}:[0-9]{2})\u005Cs+by:(?<merchant>[^:]+):(?<method>[^:]+):(?<ref>[A-Za-z0-9]+)",
  "amountTemplate": "{{amount}}",
  "descriptionSource": "template",
  "descriptionValue": "{{merchant}} {{method}}",
  "type": "withdrawal",
  "datePattern": "{{date}}",
  "dateFormat": "yyyy-MM-dd HH:mm:ss",
  "remarksPattern": "",
  "remarksTemplate": "Ref: {{ref}} | Account: {{account}}"
}
```

When actually answering the user, omit the Markdown fence shown in this documentation and return only the JSON object.
