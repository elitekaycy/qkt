package com.qkt.positions

import java.util.concurrent.atomic.AtomicLong

/** Monotonic source of engine-internal PRIMARY leg ids, one sequence per ledger. */
internal class PrimaryLegIds {
    private val primaryLegSeq = AtomicLong()

    fun next(
        strategyId: String,
        symbol: String,
    ): String = "$strategyId-$symbol-primary-${primaryLegSeq.incrementAndGet()}"
}
