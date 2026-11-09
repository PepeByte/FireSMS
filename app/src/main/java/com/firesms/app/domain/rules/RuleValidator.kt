package com.firesms.app.domain.rules

import java.time.format.DateTimeFormatter
import java.util.regex.PatternSyntaxException

/** A validation problem and the rule field (its JSON key, e.g. `bodyPattern`) it belongs to. */
data class RuleFieldError(val field: String, val message: String)

/**
 * Validates the body-pattern-dependent parts of a parser rule. Shared by the JSON importer and
 * the rule editor so a rule that saves is a rule the parser can use.
 */
object RuleValidator {

    val DESCRIPTION_SOURCES = setOf("template", "regex_group")
    val TRANSACTION_TYPES = setOf("withdrawal", "deposit", "transfer")

    /** The rule editor's names for each JSON key, so messages match what the user sees. */
    val FIELD_LABELS: Map<String, String> = mapOf(
        "bodyPattern" to "Body pattern",
        "amountTemplate" to "Amount template",
        "descriptionSource" to "Description source",
        "descriptionValue" to "Description value",
        "type" to "Transaction type",
        "datePattern" to "Date pattern",
        "dateFormat" to "Date format",
        "remarksPattern" to "Remarks pattern",
        "remarksTemplate" to "Remarks template"
    )

    /** An example shown whenever a message asks the user to add a named group. */
    const val NAMED_GROUP_EXAMPLE = """(?<amount>[\d,.]+)"""

    private val NAMED_GROUP = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")
    private val PLACEHOLDER = Regex("""\{\{(\w+).*?\}\}""")

    /** The editor label for [key], or the key itself when it has none. */
    fun labelFor(key: String): String = FIELD_LABELS[key] ?: key

    /**
     * Returns the first problem found, or null when the fields are usable. [label] maps a field's
     * JSON key (e.g. `bodyPattern`) to the name shown to the user.
     */
    fun validate(
        bodyPattern: String,
        amountTemplate: String,
        descriptionSource: String,
        descriptionValue: String,
        type: String,
        datePattern: String,
        dateFormat: String,
        remarksPattern: String,
        remarksTemplate: String,
        label: (String) -> String = ::labelFor
    ): String? = validateFields(
        bodyPattern, amountTemplate, descriptionSource, descriptionValue, type,
        datePattern, dateFormat, remarksPattern, remarksTemplate, label
    )?.message

    /** Like [validate], but also says which field the problem is in. */
    fun validateFields(
        bodyPattern: String,
        amountTemplate: String,
        descriptionSource: String,
        descriptionValue: String,
        type: String,
        datePattern: String,
        dateFormat: String,
        remarksPattern: String,
        remarksTemplate: String,
        label: (String) -> String = ::labelFor
    ): RuleFieldError? {
        fun error(field: String, message: String) = RuleFieldError(field, message)

        if (bodyPattern.isBlank()) return error("bodyPattern", "${label("bodyPattern")} cannot be blank.")
        patternProblem(bodyPattern, RegexOption.DOT_MATCHES_ALL)?.let {
            return error("bodyPattern", "${label("bodyPattern")} isn't a valid pattern: $it")
        }

        if (amountTemplate.isBlank()) {
            return error("amountTemplate", "${label("amountTemplate")} cannot be blank. Use {{amount}} for a group named amount.")
        }
        if (descriptionSource !in DESCRIPTION_SOURCES) {
            return error(
                "descriptionSource",
                "${label("descriptionSource")} must be either \"template\" or \"regex_group\"."
            )
        }
        if (descriptionValue.isBlank()) {
            return error("descriptionValue", "${label("descriptionValue")} cannot be blank.")
        }
        if (type !in TRANSACTION_TYPES) {
            return error("type", "${label("type")} must be \"withdrawal\", \"deposit\", or \"transfer\".")
        }

        val namedGroups = namedGroups(bodyPattern)
        val body = label("bodyPattern")
        validateTemplate(label("amountTemplate"), amountTemplate, namedGroups, body)
            ?.let { return error("amountTemplate", it) }
        if (descriptionSource == "regex_group") {
            val group = descriptionValue.trim()
            if (group !in namedGroups) {
                return error(
                    "descriptionValue",
                    "${label("descriptionValue")} names the group \"$group\", " + missingGroupHint(group, namedGroups, body)
                )
            }
        } else {
            validateTemplate(label("descriptionValue"), descriptionValue, namedGroups, body)
                ?.let { return error("descriptionValue", it) }
        }

        if (datePattern.isBlank() != dateFormat.isBlank()) {
            val missing = if (datePattern.isBlank()) "datePattern" else "dateFormat"
            return error(
                missing,
                "${label("datePattern")} and ${label("dateFormat")} must either both be set or both be blank."
            )
        }
        validateOptionalPattern(label("datePattern"), datePattern, namedGroups, body)
            ?.let { return error("datePattern", it) }
        if (dateFormat.isNotBlank()) {
            try {
                DateTimeFormatter.ofPattern(dateFormat)
            } catch (e: IllegalArgumentException) {
                return error(
                    "dateFormat",
                    "${label("dateFormat")} isn't a valid date format (${e.message ?: "unknown letter"}). " +
                        "Use letters such as dd-MM-yyyy or dd-MMM-yy HH:mm."
                )
            }
        }

        validateOptionalPattern(label("remarksPattern"), remarksPattern, namedGroups, body)
            ?.let { return error("remarksPattern", it) }
        return validateTemplate(label("remarksTemplate"), remarksTemplate, namedGroups, body)
            ?.let { error("remarksTemplate", it) }
    }

    /** Named groups in [pattern], in the order they appear. */
    fun namedGroups(pattern: String): Set<String> =
        NAMED_GROUP.findAll(pattern).mapTo(linkedSetOf()) { it.groupValues[1] }

    /**
     * Describes why [pattern] does not compile, in plain words with the position of the problem,
     * or returns null when it compiles.
     */
    fun patternProblem(pattern: String, vararg options: RegexOption): String? = try {
        Regex(pattern, options.toSet())
        null
    } catch (e: PatternSyntaxException) {
        describePatternError(e)
    } catch (e: IllegalArgumentException) {
        e.message ?: "it could not be read."
    }

    private fun describePatternError(e: PatternSyntaxException): String {
        val description = e.description?.trimEnd('.')?.ifBlank { null } ?: "it could not be read"
        val pattern = e.pattern.orEmpty()
        if (e.index < 0 || pattern.isEmpty()) return "$description."
        // Show a few characters around the problem; positions count from 1 for non-programmers.
        val position = e.index.coerceAtMost(pattern.length)
        val start = (position - 8).coerceAtLeast(0)
        val end = (position + 8).coerceAtMost(pattern.length)
        val excerpt = pattern.substring(start, end)
        return "$description at character ${position + 1} (near \"$excerpt\")."
    }

    private fun missingGroupHint(group: String, namedGroups: Set<String>, bodyLabel: String): String {
        val available = if (namedGroups.isEmpty()) {
            "$bodyLabel has no named groups yet."
        } else {
            "$bodyLabel only has: ${namedGroups.joinToString()}."
        }
        val example = if (group == "amount") NAMED_GROUP_EXAMPLE else "(?<$group>…)"
        return "but $bodyLabel doesn't capture a value called \"$group\". $available " +
            "Wrap the part of the message that holds it in $example."
    }

    private fun validateTemplate(
        fieldName: String,
        template: String,
        namedGroups: Set<String>,
        bodyLabel: String
    ): String? {
        val missingGroup = PLACEHOLDER.findAll(template)
            .map { it.groupValues[1] }
            .firstOrNull { it !in namedGroups }
            ?: return null
        return "$fieldName uses {{$missingGroup}}, " + missingGroupHint(missingGroup, namedGroups, bodyLabel)
    }

    private fun validateOptionalPattern(
        fieldName: String,
        pattern: String,
        namedGroups: Set<String>,
        bodyLabel: String
    ): String? {
        if (pattern.isBlank()) return null

        if (pattern.startsWith("{{")) {
            val name = pattern.removeSurrounding("{{", "}}").trim()
            return if (name in namedGroups) null
            else "$fieldName uses {{$name}}, " + missingGroupHint(name, namedGroups, bodyLabel)
        }

        return patternProblem(pattern)?.let { "$fieldName isn't a valid pattern: $it" }
    }
}
