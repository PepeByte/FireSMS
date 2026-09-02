package com.firesms.app.domain.parser

import com.firesms.app.data.local.ParserRuleDao
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.domain.model.ParsedTransaction
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class SmsParser(private val ruleDao: ParserRuleDao) {

    companion object {
        /**
         * Pure helper: returns true when any enabled rule's senderPattern regex matches [sender].
         * Skips rules with invalid regex patterns. Returns false when the list is empty or
         * all matching rules are disabled.
         */
        fun senderMatchesAnyEnabledRule(sender: String, rules: List<ParserRule>): Boolean {
            for (rule in rules) {
                if (!rule.enabled) continue
                try {
                    val regex = Regex(rule.senderPattern)
                    if (regex.containsMatchIn(sender)) return true
                } catch (_: IllegalArgumentException) {
                    // Invalid regex, skip this rule
                }
            }
            return false
        }
    }

    /**
     * Suspending wrapper that uses the DAO to fetch enabled rules.
     */
    suspend fun senderMatchesAnyEnabledRule(sender: String): Boolean {
        val rules = ruleDao.getAllEnabledOrderedByPriority().first()
        return senderMatchesAnyEnabledRule(sender, rules)
    }

    suspend fun parse(sender: String, body: String, receivedAt: Long): ParsedTransaction? {
        val rules = ruleDao.getAllEnabledOrderedByPriority().first()
        for (rule in rules) {
            val senderMatch = try {
                Regex(rule.senderPattern).containsMatchIn(sender)
            } catch (_: IllegalArgumentException) {
                false
            }
            if (!senderMatch) continue

            val bodyMatch = try {
                Regex(rule.bodyPattern, RegexOption.DOT_MATCHES_ALL).find(body)
            } catch (_: IllegalArgumentException) {
                null
            }
            val groups = bodyMatch?.groups ?: continue

            val amount = extractAmount(groups, rule.amountTemplate) ?: continue
            val description = extractDescription(groups, rule)
            val sourceAccount = rule.sourceAccountName
                ?: rule.sourceAccountKeyword?.let { keyword ->
                    body.split(" ").find { it.contains(keyword, ignoreCase = true) }
                }
            val parsedDate = extractDate(groups, body, rule, receivedAt)
            val remarks = extractRemarks(groups, rule)

            return ParsedTransaction(
                amount = cleanNumber(amount),
                description = description,
                sourceAccountName = sourceAccount,
                sourceAccountId = rule.sourceAccountId,
                destinationAccountName = rule.destinationAccountName,
                transactionType = rule.type,
                date = parsedDate,
                notes = remarks
            )
        }
        return null
    }

    suspend fun testRule(rule: ParserRule, sender: String, body: String): ParsedTransaction? {
        val senderMatch = try {
            Regex(rule.senderPattern).containsMatchIn(sender)
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!senderMatch) return null

        val bodyMatch = try {
            Regex(rule.bodyPattern, RegexOption.DOT_MATCHES_ALL).find(body)
        } catch (_: IllegalArgumentException) {
            null
        }
        val groups = bodyMatch?.groups ?: return null

        val amount = extractAmount(groups, rule.amountTemplate) ?: return null
        val description = extractDescription(groups, rule)
        val sourceAccount = rule.sourceAccountName
            ?: rule.sourceAccountKeyword?.let { keyword ->
                body.split(" ").find { it.contains(keyword, ignoreCase = true) }
            }
        val parsedDate = extractDate(groups, body, rule, System.currentTimeMillis())
        val remarks = extractRemarks(groups, rule)

        return ParsedTransaction(
            amount = cleanNumber(amount),
            description = description,
            sourceAccountName = sourceAccount,
            sourceAccountId = rule.sourceAccountId,
            destinationAccountName = rule.destinationAccountName,
            transactionType = rule.type,
            date = parsedDate,
            notes = remarks
        )
    }

    private fun extractAmount(groups: MatchGroupCollection, template: String): String? {
        var result = template
        val placeholderPattern = Regex("""\{\{(\w+).*?\}\}""")
        result = placeholderPattern.replace(result) { match ->
            val groupName = match.groupValues[1]
            groups[groupName]?.value ?: match.value
        }
        // If any {{placeholder}} remains, a named group was missing
        if (placeholderPattern.containsMatchIn(result)) return null
        return result.ifBlank { null }
    }

    private fun extractDescription(groups: MatchGroupCollection, rule: ParserRule): String {
        return if (rule.descriptionSource == "regex_group") {
            groups[rule.descriptionValue]?.value ?: rule.descriptionValue
        } else {
            var result = rule.descriptionValue
            val placeholderPattern = Regex("""\{\{(\w+).*?\}\}""")
            result = placeholderPattern.replace(result) { match ->
                val groupName = match.groupValues[1]
                groups[groupName]?.value ?: match.value
            }
            result
        }
    }

    private fun extractDate(
        groups: MatchGroupCollection,
        body: String,
        rule: ParserRule,
        fallback: Long
    ): Long {
        if (rule.datePattern.isNullOrBlank()) return fallback

        val dateMatch = try {
            Regex(rule.datePattern).find(body)
        } catch (_: IllegalArgumentException) {
            null
        }
        val dateStr = if (rule.datePattern.startsWith("{{")) {
            val groupName = rule.datePattern.removeSurrounding("{{", "}}")
            groups[groupName]?.value
        } else {
            dateMatch?.value
        } ?: return fallback

        if (rule.dateFormat.isNullOrBlank()) return fallback

        return try {
            val parsed = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern(rule.dateFormat, Locale.ENGLISH))
            parsed.atZone(ZoneId.systemDefault()).toEpochSecond().coerceAtLeast(0) * 1000
        } catch (_: DateTimeParseException) {
            fallback
        }
    }

    private fun extractRemarks(groups: MatchGroupCollection, rule: ParserRule): String? {
        if (rule.remarksPattern.isNullOrBlank() && rule.remarksTemplate.isNullOrBlank()) return null

        val remarksText = if (rule.remarksPattern?.startsWith("{{") == true) {
            val groupName = rule.remarksPattern!!.removeSurrounding("{{", "}}")
            groups[groupName]?.value
        } else if (!rule.remarksPattern.isNullOrBlank()) {
            try {
                Regex(rule.remarksPattern).find(groups[0]?.value ?: "")?.value
            } catch (_: IllegalArgumentException) {
                null
            }
        } else null

        if (remarksText == null && rule.remarksTemplate.isNullOrBlank()) return null

        var result = rule.remarksTemplate ?: return remarksText
        val placeholderPattern = Regex("""\{\{(\w+).*?\}\}""")
        result = placeholderPattern.replace(result) { match ->
            val groupName = match.groupValues[1]
            groups[groupName]?.value ?: match.value
        }
        return result
    }

    fun cleanNumber(raw: String): String {
        return raw.replace(Regex("[^\\d.]"), "")
    }
}
