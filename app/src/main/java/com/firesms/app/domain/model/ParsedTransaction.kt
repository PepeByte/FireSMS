package com.firesms.app.domain.model

data class ParsedTransaction(
    val amount: String,
    val description: String,
    val sourceAccountName: String? = null,
    val sourceAccountId: String? = null,
    val destinationAccountName: String? = null,
    val transactionType: String,
    val date: Long,
    val notes: String? = null,
    val categoryName: String? = null,
    val budgetName: String? = null
)
