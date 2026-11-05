package com.qkt.connector.mt5

/** Characters of a submitted order comment the MT5 venue retains. */
internal const val MT5_COMMENT_LIMIT: Int = 16

/**
 * Whether a venue-stored order comment identifies [orderId].
 *
 * Order ids end in `--<n>`. The venue keeps only the first [MT5_COMMENT_LIMIT] characters of a
 * comment, so a stored value at that length may be a prefix of the id it names and is matched
 * as one — unless the cut fell inside the trailing number, where `…--2` would also claim
 * `…--26` (#1096). A cut inside the strategy name is a plain prefix, digits or not: the venue kept
 * `dsl-xauusd_novel_accel_24` of `dsl-xauusd_novel_accel_240m--9`. A stored value that was not truncated must equal the id exactly.
 */
internal fun matchesOrderComment(
    stored: String?,
    orderId: String,
): Boolean {
    if (stored.isNullOrBlank()) return false
    if (stored == orderId) return true
    if (stored.length < MT5_COMMENT_LIMIT || !orderId.startsWith(stored)) return false
    // Only a cut inside the trailing `--<n>` can claim a sibling order; digits in the strategy
    // name ("..._240m--9" stored as "..._24") are just where the venue's limit fell.
    val sequenceStart = orderId.lastIndexOf("--").let { if (it < 0) 0 else it + 2 }
    if (stored.length < sequenceStart) return true
    val cutInsideNumber = stored.last().isDigit() && orderId[stored.length].isDigit()
    return !cutInsideNumber
}
