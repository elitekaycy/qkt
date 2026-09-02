package com.qkt.broker.mt5

/** Characters of a submitted order comment the MT5 venue retains. */
internal const val MT5_COMMENT_LIMIT: Int = 16

/**
 * Whether a venue-stored order comment identifies [orderId].
 *
 * Order ids end in `--<n>`. The venue keeps only the first [MT5_COMMENT_LIMIT] characters of a
 * comment, so a stored value at that length may be a prefix of the id it names and is matched
 * as one — unless the cut fell inside the trailing number, where `…--2` would also claim
 * `…--26` (#1096). A stored value that was not truncated must equal the id exactly.
 */
internal fun matchesOrderComment(
    stored: String?,
    orderId: String,
): Boolean {
    if (stored.isNullOrBlank()) return false
    if (stored == orderId) return true
    if (stored.length < MT5_COMMENT_LIMIT || !orderId.startsWith(stored)) return false
    val cutInsideNumber = stored.last().isDigit() && orderId[stored.length].isDigit()
    return !cutInsideNumber
}
