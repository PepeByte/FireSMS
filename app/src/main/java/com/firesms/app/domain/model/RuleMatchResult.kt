package com.firesms.app.domain.model

data class RuleMatchResult(
    val ruleId: String,
    val ruleName: String,
    val matchedGroups: Map<String, String>,
    val parsedTransaction: ParsedTransaction?
)
