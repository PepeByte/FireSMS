package com.firesms.app.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A separate Firefly transaction that was split off an SMS transaction, e.g. a friend's share of a
 * bill moved to a liability account. The SMS log keeps these so the edit screen can show and save
 * the original and its split-offs together.
 *
 * [amount], [accountName] and [description] are the last values FireSMS saved, for display only;
 * Firefly is the source of truth.
 */
@Serializable
data class SplitOffRecord(
    /** Null while the create request may not have reached Firefly; found again by [externalId]. */
    val fireflyId: String? = null,
    /** Unique per split-off, so a retried create can find a transaction that was already stored. */
    val externalId: String,
    val transactionJournalId: String? = null,
    val amount: String = "",
    val accountName: String = "",
    val description: String = "",
    /** The date it was sent with, so a create that may have timed out is looked up on the right day. */
    val date: String = "",
    /** Whether the Firefly "Related" link to the original transaction was created. */
    val linked: Boolean = false,
    /** How far saving got, so an interrupted save can be finished; see [SplitOffStatus]. */
    val status: String = SplitOffStatus.ACTIVE,
    val createdAt: Long = 0
)

/**
 * Saving a split-off changes two Firefly transactions, which cannot happen atomically. The status
 * records which step a save reached, so reopening the editor can restore what the user intended.
 */
object SplitOffStatus {
    /** Being created; the original has not been reduced by its amount yet. */
    const val CREATING = "creating"
    /** Created, and the original reduced to match. */
    const val ACTIVE = "active"
    /** Being removed; the original has not been given its amount back yet. */
    const val REMOVING = "removing"
    /** The original has its amount back; only deleting the split-off is left. */
    const val REMOVED = "removed"
}

private val splitOffJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeSplitOffs(records: List<SplitOffRecord>): String? =
    if (records.isEmpty()) null else splitOffJson.encodeToString(records)

fun decodeSplitOffs(value: String?): List<SplitOffRecord> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching { splitOffJson.decodeFromString<List<SplitOffRecord>>(value) }.getOrDefault(emptyList())
}

/** A new, unique external id for a split-off of the SMS with [smsHash]. */
fun newSplitOffExternalId(smsHash: String): String =
    "$smsHash-split-${UUID.randomUUID().toString().take(8)}"
