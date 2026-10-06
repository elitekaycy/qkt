package com.qkt.marketdata.marks

/** Where `qkt fetch --marks` reads a contract's mark and index history (a gateway's `/v1/marks`). */
fun interface MarkHistorySource {
    /** [qktSymbol]'s samples, one per window [windowMs] long starting in `[fromMs, toMs)` that had one, oldest first. */
    fun marks(
        qktSymbol: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<MarkSample>
}
