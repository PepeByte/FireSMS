package com.firesms.app.domain.rules

/**
 * Builds and explains sender patterns. Indian bank SMS arrive from headers such as `AD-HDFCBK`
 * or `VM-HDFCBK-S`: a two-letter operator/circle prefix that changes between messages, the
 * bank's six-character header, and sometimes a one-letter category suffix.
 */
object SenderPatterns {

    private const val PREFIX = "([A-Z]{2}-)?"
    private const val SUFFIX = "(-[A-Z])?"
    private const val REGEX_METACHARACTERS = """\^$.|?*+()[]{}"""

    /** `XY-HEADER` or `XY-HEADER-S`, or a bare `HEADER` of letters and digits. */
    private val BANK_HEADER = Regex("""^(?:[A-Za-z]{2}-)?([A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*)(?:-[A-Za-z])?$""")

    /** The shape [suggestFor] produces for bank headers, so it can be shown in plain words. */
    private val GENERATED_HEADER = Regex("""^\(\?i\)\^\(\[A-Z]\{2}-\)\?([A-Za-z0-9]+)\(-\[A-Z]\)\?\$$""")

    /**
     * A readable pattern that matches [sender] and the other headers the same bank uses.
     * Bank headers ignore the operator prefix and category suffix; anything else (a phone
     * number, a name) must match exactly.
     */
    fun suggestFor(sender: String): String {
        val trimmed = sender.trim()
        val header = BANK_HEADER.matchEntire(trimmed)?.groupValues?.get(1)
        // Short alphanumeric senders are bank headers; long ones are more likely names.
        return if (header != null && header.length in 3..11) {
            "(?i)^$PREFIX${header.uppercase()}$SUFFIX$"
        } else {
            "^${escape(trimmed)}$"
        }
    }

    /** Escapes regex metacharacters with a backslash each, keeping the text readable. */
    fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            if (char in REGEX_METACHARACTERS) append('\\')
            append(char)
        }
    }

    /** One-line explanation of what a generated pattern matches, or null for other patterns. */
    fun explain(pattern: String): String? {
        GENERATED_HEADER.matchEntire(pattern)?.let {
            val header = it.groupValues[1]
            return "Matches $header with any operator prefix, e.g. AD-$header, VM-$header or JD-$header-S."
        }
        return exactSender(pattern)?.let { "Matches only the sender $it." }
    }

    /**
     * A short label for a sender pattern in lists: the bank header or exact sender when the
     * pattern is one of the simple shapes, otherwise the pattern itself.
     */
    fun describe(pattern: String): String {
        GENERATED_HEADER.matchEntire(pattern)?.let { return "${it.groupValues[1]} (any prefix)" }
        exactSender(pattern)?.let { return it }
        return pattern
    }

    /**
     * The literal sender of `^literal$`, `\Qliteral\E` (from older versions) or a pattern without
     * any special characters, or null when the pattern does more than match text.
     */
    private fun exactSender(pattern: String): String? {
        if (pattern.startsWith("\\Q") && pattern.endsWith("\\E") && pattern.length >= 4) {
            val literal = pattern.substring(2, pattern.length - 2)
            return literal.takeIf { "\\E" !in it && it.isNotEmpty() }
        }
        val body = pattern.removePrefix("^").removeSuffix("$")
        if (body.isEmpty()) return null
        val literal = StringBuilder()
        var index = 0
        while (index < body.length) {
            val char = body[index]
            when {
                char == '\\' && index + 1 < body.length && body[index + 1] in REGEX_METACHARACTERS -> {
                    literal.append(body[index + 1])
                    index += 2
                }
                char in REGEX_METACHARACTERS -> return null
                else -> {
                    literal.append(char)
                    index++
                }
            }
        }
        return literal.toString()
    }
}
