package com.firesms.app.domain.parser

import com.firesms.app.data.local.ParserRuleDao
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.domain.model.ParsedTransaction
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.TemporalQuery
import java.util.Locale

class SmsParser(private val ruleDao: ParserRuleDao) {

    companion object {
        private val PLACEHOLDER = Regex("""\{\{(\w+).*?\}\}""")
        private val WHITESPACE = Regex("""\s+""")
        private val NON_AMOUNT_CHARS = Regex("""[^\d.]""")

        /**
         * Pure helper: returns true when any enabled rule's senderPattern regex matches [sender].
         * Skips rules with invalid regex patterns. Returns false when the list is empty or
         * all matching rules are disabled.
         */
        fun senderMatchesAnyEnabledRule(sender: String, rules: List<ParserRule>): Boolean =
            rules.any { it.enabled && compile(it.senderPattern)?.containsMatchIn(sender) == true }

        /**
         * Applies a single rule to an SMS. Returns null when the sender or body does not match,
         * or when the amount cannot be resolved to a positive number. A misconfigured rule never
         * throws, so later rules still get a chance to match.
         */
        fun applyRule(rule: ParserRule, sender: String, body: String, receivedAt: Long): ParsedTransaction? {
            if (compile(rule.senderPattern)?.containsMatchIn(sender) != true) return null
            val groups = compile(rule.bodyPattern, RegexOption.DOT_MATCHES_ALL)?.find(body)?.groups
                ?: return null
            val amount = extractAmount(groups, rule.amountTemplate) ?: return null

            // The account picked in the editor is one of the user's own (asset) accounts. Firefly
            // requires that account on the destination side of a deposit.
            val ownAccountName = rule.sourceAccountName
                ?: rule.sourceAccountKeyword?.let { keywordToken(body, it) }
            val ownAccountId = rule.sourceAccountId
            val ownAccountIsDestination = rule.type == "deposit" &&
                (ownAccountName != null || ownAccountId != null)

            return ParsedTransaction(
                amount = amount,
                description = extractDescription(groups, rule),
                sourceAccountName = if (ownAccountIsDestination) rule.destinationAccountName else ownAccountName,
                sourceAccountId = if (ownAccountIsDestination) null else ownAccountId,
                destinationAccountName = if (ownAccountIsDestination) ownAccountName else rule.destinationAccountName,
                destinationAccountId = if (ownAccountIsDestination) ownAccountId else null,
                transactionType = rule.type,
                date = extractDate(groups, body, rule, receivedAt),
                notes = extractRemarks(groups, rule)
            )
        }

        /**
         * Strips currency symbols and thousands separators. Returns null unless the result is a
         * positive decimal number that Firefly will accept.
         */
        fun normalizeAmount(raw: String): String? {
            val cleaned = raw.replace(NON_AMOUNT_CHARS, "").trim('.')
            val value = cleaned.toBigDecimalOrNull() ?: return null
            return value.takeIf { it.signum() > 0 }?.toPlainString()
        }

        private fun compile(pattern: String, vararg options: RegexOption): Regex? = try {
            Regex(pattern, options.toSet())
        } catch (_: IllegalArgumentException) {
            null
        }

        /** Named-group lookup that returns null instead of throwing when the group does not exist. */
        private fun MatchGroupCollection.value(name: String): String? = try {
            get(name)?.value
        } catch (_: IllegalArgumentException) {
            null
        }

        /** Returns the group name when [pattern] is a single `{{group}}` placeholder. */
        private fun placeholderName(pattern: String): String? =
            if (pattern.startsWith("{{")) pattern.removeSurrounding("{{", "}}").trim() else null

        private fun fillTemplate(template: String, groups: MatchGroupCollection): String =
            PLACEHOLDER.replace(template) { match -> groups.value(match.groupValues[1]).orEmpty() }

        private fun extractAmount(groups: MatchGroupCollection, template: String): String? {
            val missingGroup = PLACEHOLDER.findAll(template).any { groups.value(it.groupValues[1]) == null }
            if (missingGroup) return null
            return normalizeAmount(fillTemplate(template, groups))
        }

        private fun extractDescription(groups: MatchGroupCollection, rule: ParserRule): String {
            val text = if (rule.descriptionSource == "regex_group") {
                groups.value(rule.descriptionValue.trim()) ?: rule.descriptionValue
            } else {
                fillTemplate(rule.descriptionValue, groups)
            }
            return text.replace(WHITESPACE, " ").trim().ifBlank { rule.name }
        }

        private fun extractDate(
            groups: MatchGroupCollection,
            body: String,
            rule: ParserRule,
            receivedAt: Long
        ): Long {
            val pattern = rule.datePattern?.takeIf { it.isNotBlank() } ?: return receivedAt
            val format = rule.dateFormat?.takeIf { it.isNotBlank() } ?: return receivedAt
            val dateText = placeholderName(pattern)
                ?.let { groups.value(it) }
                ?: if (pattern.startsWith("{{")) null else compile(pattern)?.find(body)?.value
            return dateText?.let { parseDate(it.trim(), format, receivedAt) } ?: receivedAt
        }

        /**
         * Parses [text] with a Java DateTimeFormatter pattern. Month and AM/PM names are matched
         * case-insensitively. Date-only formats keep the SMS received time of day.
         */
        private fun parseDate(text: String, format: String, receivedAt: Long): Long? {
            val zone = ZoneId.systemDefault()
            return try {
                val formatter = DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(format)
                    .toFormatter(Locale.ENGLISH)
                val dateTime = when (
                    val parsed = formatter.parseBest(
                        text,
                        TemporalQuery(LocalDateTime::from),
                        TemporalQuery(LocalDate::from)
                    )
                ) {
                    is LocalDateTime -> parsed
                    is LocalDate -> parsed.atTime(Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalTime())
                    else -> return null
                }
                dateTime.atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeException) {
                null
            } catch (_: IllegalArgumentException) {
                // Invalid dateFormat pattern.
                null
            }
        }

        /**
         * remarksTemplate takes precedence. Otherwise remarksPattern is either a `{{group}}`
         * placeholder or a regex searched within the matched part of the body.
         */
        private fun extractRemarks(groups: MatchGroupCollection, rule: ParserRule): String? {
            rule.remarksTemplate?.takeIf { it.isNotBlank() }?.let { template ->
                return fillTemplate(template, groups).trim().ifBlank { null }
            }
            val pattern = rule.remarksPattern?.takeIf { it.isNotBlank() } ?: return null
            val remarks = placeholderName(pattern)?.let { groups.value(it) }
                ?: if (pattern.startsWith("{{")) null else compile(pattern)?.find(groups[0]?.value.orEmpty())?.value
            return remarks?.trim()?.ifBlank { null }
        }

        /** The first whitespace-separated word of [body] containing [keyword], without punctuation. */
        private fun keywordToken(body: String, keyword: String): String? =
            body.split(WHITESPACE)
                .firstOrNull { it.contains(keyword, ignoreCase = true) }
                ?.trim { !it.isLetterOrDigit() }
                ?.ifBlank { null }
    }

    /**
     * Suspending wrapper that uses the DAO to fetch enabled rules.
     */
    suspend fun senderMatchesAnyEnabledRule(sender: String): Boolean {
        val rules = ruleDao.getAllEnabledOrderedByPriority().first()
        return senderMatchesAnyEnabledRule(sender, rules)
    }

    suspend fun parse(sender: String, body: String, receivedAt: Long): ParsedTransaction? =
        ruleDao.getAllEnabledOrderedByPriority().first()
            .firstNotNullOfOrNull { rule -> applyRule(rule, sender, body, receivedAt) }

    fun testRule(rule: ParserRule, sender: String, body: String): ParsedTransaction? =
        applyRule(rule, sender, body, System.currentTimeMillis())
}
