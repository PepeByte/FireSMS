package com.firesms.app.domain.rules

import java.time.format.DateTimeFormatter

/**
 * Validates the body-pattern-dependent parts of a parser rule. Shared by the JSON importer and
 * the rule editor so a rule that saves is a rule the parser can use.
 */
object RuleValidator {

    val DESCRIPTION_SOURCES = setOf("template", "regex_group")
    val TRANSACTION_TYPES = setOf("withdrawal", "deposit", "transfer")

    private val NAMED_GROUP = Regex("""\(\?<([a-zA-Z][a-zA-Z0-9]*)>""")
    private val PLACEHOLDER = Regex("""\{\{(\w+).*?\}\}""")

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
        label: (String) -> String = { it }
    ): String? {
        if (bodyPattern.isBlank()) return "${label("bodyPattern")} cannot be blank."
        try {
            Regex(bodyPattern, RegexOption.DOT_MATCHES_ALL)
        } catch (_: IllegalArgumentException) {
            return "${label("bodyPattern")} is not a valid Kotlin/Java regular expression."
        }

        if (amountTemplate.isBlank()) return "${label("amountTemplate")} cannot be blank."
        if (descriptionSource !in DESCRIPTION_SOURCES) {
            return "${label("descriptionSource")} must be either \"template\" or \"regex_group\"."
        }
        if (descriptionValue.isBlank()) return "${label("descriptionValue")} cannot be blank."
        if (type !in TRANSACTION_TYPES) {
            return "${label("type")} must be \"withdrawal\", \"deposit\", or \"transfer\"."
        }

        val namedGroups = namedGroups(bodyPattern)
        val body = label("bodyPattern")
        validateTemplate(label("amountTemplate"), amountTemplate, namedGroups, body)?.let { return it }
        if (descriptionSource == "regex_group") {
            if (descriptionValue.trim() !in namedGroups) {
                return "${label("descriptionValue")} references a group that is missing from $body."
            }
        } else {
            validateTemplate(label("descriptionValue"), descriptionValue, namedGroups, body)?.let { return it }
        }

        if (datePattern.isBlank() != dateFormat.isBlank()) {
            return "${label("datePattern")} and ${label("dateFormat")} must either both be set or both be blank."
        }
        validateOptionalPattern(label("datePattern"), datePattern, namedGroups, body)?.let { return it }
        if (dateFormat.isNotBlank()) {
            try {
                DateTimeFormatter.ofPattern(dateFormat)
            } catch (_: IllegalArgumentException) {
                return "${label("dateFormat")} is not a valid Java date format pattern."
            }
        }

        validateOptionalPattern(label("remarksPattern"), remarksPattern, namedGroups, body)?.let { return it }
        return validateTemplate(label("remarksTemplate"), remarksTemplate, namedGroups, body)
    }

    fun namedGroups(pattern: String): Set<String> =
        NAMED_GROUP.findAll(pattern).mapTo(mutableSetOf()) { it.groupValues[1] }

    private fun validateTemplate(
        fieldName: String,
        template: String,
        namedGroups: Set<String>,
        bodyLabel: String
    ): String? {
        val missingGroup = PLACEHOLDER.findAll(template)
            .map { it.groupValues[1] }
            .firstOrNull { it !in namedGroups }
        return missingGroup?.let { "$fieldName references {{$it}}, which is missing from $bodyLabel." }
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
            else "$fieldName references {{$name}}, which is missing from $bodyLabel."
        }

        return try {
            Regex(pattern)
            null
        } catch (_: IllegalArgumentException) {
            "$fieldName is not a valid Kotlin/Java regular expression."
        }
    }
}
