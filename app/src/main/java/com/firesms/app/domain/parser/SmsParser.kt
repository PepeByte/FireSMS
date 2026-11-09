package com.firesms.app.domain.parser

import com.firesms.app.data.local.ParserRuleDao
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.domain.model.ParsedTransaction
import com.firesms.app.domain.rules.RuleAccounts
import com.firesms.app.domain.rules.RuleValidator
import com.firesms.app.domain.rules.withLegacyDepositAccountResolved
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

/** How a matched rule found the transaction date. */
sealed interface DateResolution {
    val millis: Long

    /** The rule has no date pattern and format, so the SMS received time is used. */
    data class NotConfigured(override val millis: Long) : DateResolution

    /** [text] from the message was parsed with the rule's date format. */
    data class Parsed(override val millis: Long, val text: String) : DateResolution

    /** The date could not be read, so the SMS received time is used instead. */
    data class Fallback(override val millis: Long, val reason: String) : DateResolution
}

/** The outcome of testing one rule against one SMS, with the reason when it does not match. */
sealed interface RuleDiagnosis {
    /** [field] does not compile; [detail] is the regex engine's message. */
    data class InvalidPattern(val field: String, val detail: String) : RuleDiagnosis

    /** The sender pattern did not match [sender]. */
    data class SenderMismatch(val sender: String) : RuleDiagnosis

    /** The body pattern found nothing in the message. */
    data object BodyMismatch : RuleDiagnosis

    /**
     * The body matched, but the amount [template] resolved to [resolved], which is not a
     * positive number. [missingGroups] are placeholders the match did not fill.
     */
    data class AmountInvalid(
        val template: String,
        val resolved: String,
        val missingGroups: List<String>,
        val groups: List<Pair<String, String?>>
    ) : RuleDiagnosis

    /** The rule matched. [groups] lists every named group in body-pattern order. */
    data class Matched(
        val transaction: ParsedTransaction,
        val groups: List<Pair<String, String?>>,
        val date: DateResolution
    ) : RuleDiagnosis
}

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

            val accounts = rule.withLegacyDepositAccountResolved()
            val ownName = accounts.sourceAccountName
                ?: accounts.sourceAccountKeyword?.let { keywordToken(body, it) }
            val ownId = accounts.sourceAccountId
            val otherName = accounts.destinationAccountName
            val otherId = accounts.destinationAccountId
            val ownIsDestination = RuleAccounts.ownAccountIsDestination(rule.type)

            return ParsedTransaction(
                amount = amount,
                description = extractDescription(groups, rule),
                sourceAccountName = if (ownIsDestination) otherName else ownName,
                sourceAccountId = if (ownIsDestination) otherId else ownId,
                destinationAccountName = if (ownIsDestination) ownName else otherName,
                destinationAccountId = if (ownIsDestination) ownId else otherId,
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
        ): Long = resolveDate(groups, body, rule, receivedAt).millis

        private fun resolveDate(
            groups: MatchGroupCollection,
            body: String,
            rule: ParserRule,
            receivedAt: Long
        ): DateResolution {
            val pattern = rule.datePattern?.takeIf { it.isNotBlank() }
            val format = rule.dateFormat?.takeIf { it.isNotBlank() }
            if (pattern == null || format == null) return DateResolution.NotConfigured(receivedAt)
            val dateText = placeholderName(pattern)
                ?.let { groups.value(it) }
                ?: if (pattern.startsWith("{{")) null else compile(pattern)?.find(body)?.value
            if (dateText == null) {
                return DateResolution.Fallback(receivedAt, "the date pattern '$pattern' found no date in the message")
            }
            val text = dateText.trim()
            return parseDate(text, format, receivedAt)
                ?.let { DateResolution.Parsed(it, text) }
                ?: DateResolution.Fallback(receivedAt, "'$text' doesn't match the date format '$format'")
        }

        /**
         * Applies [rule] like [applyRule], but explains the outcome: which step failed, or every
         * named group and how the date was found. For the rule editor; [applyRule] stays the
         * fast path for incoming SMS.
         */
        fun diagnoseRule(rule: ParserRule, sender: String, body: String, receivedAt: Long): RuleDiagnosis {
            val senderRegex = try {
                Regex(rule.senderPattern)
            } catch (e: IllegalArgumentException) {
                return RuleDiagnosis.InvalidPattern("Sender pattern", e.message.orEmpty())
            }
            if (!senderRegex.containsMatchIn(sender)) return RuleDiagnosis.SenderMismatch(sender)
            val bodyRegex = try {
                Regex(rule.bodyPattern, RegexOption.DOT_MATCHES_ALL)
            } catch (e: IllegalArgumentException) {
                return RuleDiagnosis.InvalidPattern("Body pattern", e.message.orEmpty())
            }
            val groups = bodyRegex.find(body)?.groups ?: return RuleDiagnosis.BodyMismatch
            val groupValues = RuleValidator.namedGroups(rule.bodyPattern).map { it to groups.value(it) }

            val missing = PLACEHOLDER.findAll(rule.amountTemplate)
                .map { it.groupValues[1] }
                .filter { groups.value(it) == null }
                .distinct()
                .toList()
            val filledAmount = fillTemplate(rule.amountTemplate, groups)
            if (missing.isNotEmpty() || normalizeAmount(filledAmount) == null) {
                return RuleDiagnosis.AmountInvalid(rule.amountTemplate, filledAmount, missing, groupValues)
            }

            // Every step above passed, so applyRule matches too.
            val transaction = applyRule(rule, sender, body, receivedAt)
                ?: return RuleDiagnosis.BodyMismatch
            return RuleDiagnosis.Matched(
                transaction = transaction,
                groups = groupValues,
                date = resolveDate(groups, body, rule, receivedAt)
            )
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
