package com.qkt.dsl.compile

/**
 * Identity for a sync group managed by [CandleHub] — a set of named streams whose
 * closed bars are delivered together as one atomic event keyed by their shared
 * window end. Comes from a parsed `SYNCHRONIZE <a> <b> [WITHIN <duration>]`
 * clause in a strategy's `SYMBOLS` block.
 *
 * e.g. `SyncGroupKey(members = mapOf("gold" to goldKey, "silver" to silverKey),
 * timeoutMs = 1000)` waits up to 1 s for both bars to print on the same window
 * before firing.
 *
 * `members` maps each declared alias (`gold`) to the underlying [HubKey] so
 * listeners can look up the right bar per alias. `timeoutMs` is the soft deadline
 * for a window to complete — `null` means "wait forever for both bars."
 */
data class SyncGroupKey(
    val members: Map<String, HubKey>,
    val timeoutMs: Long?,
) {
    init {
        require(members.size >= 2) {
            "SyncGroupKey needs at least 2 members, got ${members.size}"
        }
        require(members.keys.all { it.isNotBlank() }) {
            "SyncGroupKey alias keys must not be blank: ${members.keys}"
        }
        require(timeoutMs == null || timeoutMs > 0) {
            "SyncGroupKey.timeoutMs must be positive when present: $timeoutMs"
        }
    }
}
