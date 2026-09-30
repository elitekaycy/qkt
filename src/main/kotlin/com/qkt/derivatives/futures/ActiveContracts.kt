package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentRegistry
import java.util.concurrent.ConcurrentHashMap

/** The contract a futures stream follows at an instant: its [code] (no venue), [expiryMs], and when it next rolls. */
data class ActiveContract(
    val code: String,
    val expiryMs: Long,
    val nextRollMs: Long,
)

/**
 * Resolves which contract a futures stream follows at a given time, from [instruments]' futures
 * directory: a listed contract follows itself until its expiry (and "rolls" at expiry); a
 * continuous stream follows its roll schedule and next rolls at the schedule's next roll instant.
 * Every other symbol has none. Each stream's schedule is built once.
 */
class ActiveContracts(
    val instruments: InstrumentRegistry,
) {
    private val schedules = ConcurrentHashMap<String, Followed>()

    /** The contract [symbol] follows at [nowMs], or null (not futures, before or after its chain). */
    fun at(
        symbol: String,
        nowMs: Long,
    ): ActiveContract? {
        val terms = instruments.lookup(symbol)?.derivative as? FutureTerms ?: return null
        val expiry = terms.expiryMs
        if (expiry !=
            null
        ) {
            return if (nowMs < expiry) ActiveContract(symbol.substringAfter(':'), expiry, expiry) else null
        }
        val followed = schedules.computeIfAbsent(symbol) { followed(it, terms.root) }
        val schedule = followed.schedule ?: return null
        val contract =
            schedule.indexAt(nowMs, followed.selector ?: return null)?.let(schedule.contracts::get) ?: return null
        val nextRoll = schedule.transitions.firstOrNull { it.atMs > nowMs }?.atMs ?: contract.expiryMs
        return ActiveContract(contract.symbol, contract.expiryMs, minOf(nextRoll, contract.expiryMs))
    }

    private fun followed(
        symbol: String,
        rootId: String,
    ): Followed {
        val directory = instruments.futures() ?: return Followed(null, null)
        val policy = directory.root(rootId)?.roll
        val catalog = directory.catalog(rootId)
        val selector = ContinuousSelector.entries.firstOrNull { it.symbolFor(rootId) == symbol }
        if (policy == null || catalog == null || selector == null) return Followed(null, null)
        return Followed(RollSchedule(catalog.contracts, policy), selector)
    }

    private class Followed(
        val schedule: RollSchedule?,
        val selector: ContinuousSelector?,
    )
}
