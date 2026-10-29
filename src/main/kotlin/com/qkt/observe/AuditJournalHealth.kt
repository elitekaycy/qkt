package com.qkt.observe

/**
 * What an [EngineAuditJournal] has kept and lost, read by `qkt status` and the daemon's `/health`
 * and `/list`. Decision events (signals, risk decisions, orders, acks, fills, bookings, halts) are
 * never shed by the queue, so [lostDecisionEvents] counts only records a failed disk write lost;
 * [droppedMarketData] counts ticks and candles shed while the queue was full or lost to a failed
 * write. [omittedChainQuotes] are quotes of option-chain contracts the strategy neither declared,
 * ordered nor held, which are not journaled by design (the chain itself is recorded by the
 * account's chain book); they are not a loss.
 */
data class AuditJournalHealth(
    val enabled: Boolean,
    val written: Long = 0L,
    val queued: Int = 0,
    val droppedMarketData: Long = 0L,
    val lostDecisionEvents: Long = 0L,
    val omittedChainQuotes: Long = 0L,
) {
    /** Whether any record that should be in the journal is missing from it. */
    val lossy: Boolean get() = droppedMarketData > 0L || lostDecisionEvents > 0L

    companion object {
        /** Health of a session that keeps no audit journal. */
        val DISABLED = AuditJournalHealth(enabled = false)
    }
}
