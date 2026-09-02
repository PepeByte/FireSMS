package com.firesms.app.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuleJsonImporterTest {

    @Test
    fun `imports all supported fields and preserves regex escapes`() {
        val input = """
            {
              "bodyPattern": "debited\\s+NPR\\s+(?<amount>[\\d,]+).*?(?<date>\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}).*?(?<remarks>.+)",
              "amountTemplate": "{{amount}}",
              "descriptionSource": "template",
              "descriptionValue": "Bank transaction",
              "type": "withdrawal",
              "datePattern": "{{date}}",
              "dateFormat": "yyyy-MM-dd HH:mm:ss",
              "remarksPattern": "{{remarks}}",
              "remarksTemplate": "{{remarks}}"
            }
        """.trimIndent()

        val result = assertIs<RuleJsonImportResult.Success>(RuleJsonImporter.parse(input))

        assertEquals(
            "debited\\s+NPR\\s+(?<amount>[\\d,]+).*?(?<date>\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}).*?(?<remarks>.+)",
            result.fields.bodyPattern
        )
        assertEquals("{{amount}}", result.fields.amountTemplate)
        assertEquals("{{date}}", result.fields.datePattern)
        assertEquals("{{remarks}}", result.fields.remarksTemplate)
        assertEquals(false, result.repairedInvalidEscapes)
    }

    @Test
    fun `ignores unsupported fields and defaults optional fields`() {
        val input = """
            {
              "bodyPattern": "(?<amount>\\d+)",
              "amountTemplate": "{{amount}}",
              "descriptionSource": "template",
              "descriptionValue": "Imported",
              "type": "deposit",
              "senderPattern": "BANK",
              "priority": 1
            }
        """.trimIndent()

        val result = assertIs<RuleJsonImportResult.Success>(RuleJsonImporter.parse(input))

        assertEquals("", result.fields.datePattern)
        assertEquals("", result.fields.dateFormat)
        assertEquals("", result.fields.remarksPattern)
        assertEquals("", result.fields.remarksTemplate)
    }

    @Test
    fun `accepts null optional fields`() {
        val input = """
            {
              "bodyPattern": "(?<amount>\\d+).*?(?<merchant>.+)",
              "amountTemplate": "{{amount}}",
              "descriptionSource": "regex_group",
              "descriptionValue": "merchant",
              "type": "transfer",
              "datePattern": null,
              "remarksTemplate": null
            }
        """.trimIndent()

        val result = assertIs<RuleJsonImportResult.Success>(RuleJsonImporter.parse(input))
        assertEquals("", result.fields.datePattern)
        assertEquals("", result.fields.remarksTemplate)
    }

    @Test
    fun `accepts JSON wrapped in a markdown code fence`() {
        val input = """
            ```json
            {
              "bodyPattern": "(?<amount>\\d+)",
              "amountTemplate": "{{amount}}",
              "descriptionSource": "template",
              "descriptionValue": "Imported",
              "type": "withdrawal"
            }
            ```
        """.trimIndent()

        assertIs<RuleJsonImportResult.Success>(RuleJsonImporter.parse(input))
    }

    @Test
    fun `repairs unescaped regex backslashes emitted by an LLM`() {
        val input = """
            {
              "bodyPattern": "credited\s+by\s+(?<amount>\d+)",
              "amountTemplate": "{{amount}}",
              "descriptionSource": "template",
              "descriptionValue": "Imported",
              "type": "deposit"
            }
        """.trimIndent()

        val result = assertIs<RuleJsonImportResult.Success>(RuleJsonImporter.parse(input))
        assertEquals("credited\\s+by\\s+(?<amount>\\d+)", result.fields.bodyPattern)
        assertTrue(result.repairedInvalidEscapes)
    }

    @Test
    fun `imports the exact single-backslash rule shape reported by the user`() {
        val input = """
            {
              "bodyPattern": "Dear\s+user,\s+Your\s+account\s+(?<account>#+[0-9]+)\s+is\s+debited\s+by\s+NPR\s+(?<amount>[0-9,]+(?:[.][0-9]{1,2})?)\s+on\s+(?<date>[0-9]{4}-[0-9]{2}-[0-9]{2}\s+[0-9]{2}:[0-9]{2}:[0-9]{2})\s+by:(?<merchant>[^:]+):(?<method>[^:]+):(?<ref>[A-Za-z0-9]+)",
              "amountTemplate": "{{amount}}",
              "descriptionSource": "template",
              "descriptionValue": "{{merchant}} {{method}}",
              "type": "withdrawal",
              "datePattern": "{{date}}",
              "dateFormat": "yyyy-MM-dd HH:mm:ss",
              "remarksPattern": "",
              "remarksTemplate": "Ref: {{ref}} | Account: {{account}}"
            }
        """.trimIndent()

        val result = assertIs<RuleJsonImportResult.Success>(RuleJsonImporter.parse(input))
        assertTrue(result.repairedInvalidEscapes)
        assertTrue(result.fields.bodyPattern.startsWith("Dear\\s+user"))
        assertEquals("{{date}}", result.fields.datePattern)
    }

    @Test
    fun `accepts unicode-escaped regex backslashes`() {
        val input = """
            {
              "bodyPattern": "credited\u005Cs+by\u005Cs+(?<amount>[0-9]+)",
              "amountTemplate": "{{amount}}",
              "descriptionSource": "template",
              "descriptionValue": "Imported",
              "type": "deposit"
            }
        """.trimIndent()

        val result = assertIs<RuleJsonImportResult.Success>(RuleJsonImporter.parse(input))
        assertEquals("credited\\s+by\\s+(?<amount>[0-9]+)", result.fields.bodyPattern)
        assertEquals(false, result.repairedInvalidEscapes)
    }

    @Test
    fun `rejects malformed JSON`() {
        val result = assertIs<RuleJsonImportResult.Error>(RuleJsonImporter.parse("{not json}"))
        assertTrue(result.message.startsWith("Invalid rule JSON"))
    }

    @Test
    fun `rejects missing required fields`() {
        val result = assertIs<RuleJsonImportResult.Error>(
            RuleJsonImporter.parse("""{"bodyPattern":"(?<amount>\\d+)"}""")
        )
        assertTrue(result.message.startsWith("Invalid rule JSON"))
    }

    @Test
    fun `rejects invalid body regex`() {
        val result = assertIs<RuleJsonImportResult.Error>(
            RuleJsonImporter.parse(validJson(bodyPattern = "[invalid"))
        )
        assertTrue(result.message.contains("regular expression"))
    }

    @Test
    fun `rejects templates that reference missing body groups`() {
        val result = assertIs<RuleJsonImportResult.Error>(
            RuleJsonImporter.parse(
                validJson().replace(
                    "\"amountTemplate\": \"{{amount}}\"",
                    "\"amountTemplate\": \"{{missing}}\""
                )
            )
        )
        assertTrue(result.message.contains("missing from bodyPattern"))
    }

    @Test
    fun `rejects incomplete date mapping`() {
        val result = assertIs<RuleJsonImportResult.Error>(
            RuleJsonImporter.parse(
                validJson().replace(
                    "\"type\": \"withdrawal\"",
                    "\"type\": \"withdrawal\", \"datePattern\": \"{{amount}}\""
                )
            )
        )
        assertTrue(result.message.contains("datePattern and dateFormat"))
    }

    @Test
    fun `rejects invalid optional regex`() {
        val result = assertIs<RuleJsonImportResult.Error>(
            RuleJsonImporter.parse(
                validJson().replace(
                    "\"type\": \"withdrawal\"",
                    "\"type\": \"withdrawal\", \"remarksPattern\": \"[invalid\""
                )
            )
        )
        assertTrue(result.message.contains("remarksPattern"))
    }

    @Test
    fun `rejects invalid enum values`() {
        val badSource = assertIs<RuleJsonImportResult.Error>(
            RuleJsonImporter.parse(validJson(descriptionSource = "group"))
        )
        assertTrue(badSource.message.contains("descriptionSource"))

        val badType = assertIs<RuleJsonImportResult.Error>(
            RuleJsonImporter.parse(validJson(type = "payment"))
        )
        assertTrue(badType.message.contains("type must be"))
    }

    private fun validJson(
        bodyPattern: String = "(?<amount>\\d+)",
        descriptionSource: String = "template",
        type: String = "withdrawal"
    ): String = """
        {
          "bodyPattern": ${jsonString(bodyPattern)},
          "amountTemplate": "{{amount}}",
          "descriptionSource": "$descriptionSource",
          "descriptionValue": "Imported",
          "type": "$type"
        }
    """.trimIndent()

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
