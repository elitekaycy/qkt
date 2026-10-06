package com.qkt.marketdata.flow

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Whether a backtest has the stored tape every flow read needs: each UTC day from the earliest window a read can
 * reach (its run start less its warmup and lookback) to the run's last must be stored for the read's symbol and
 * kind. A read of prints never fetched would see no flow and never act, so a gap is a refusal naming the fetch.
 */
object FlowCoverage {
    /** One stream's flow read: [symbol]'s [kind], first needed at [earliestMs]. */
    data class Read(
        val symbol: String,
        val kind: FlowKind,
        val earliestMs: Long,
    )

    /** Why [reads] cannot be served from [store] up to [toMs] (exclusive), or null when they can. */
    fun problem(
        store: TapeStore,
        reads: Collection<Read>,
        toMs: Long,
    ): String? =
        reads
            .groupBy { it.symbol to it.kind }
            .firstNotNullOfOrNull { (key, group) ->
                val (symbol, kind) = key
                if ('@' in symbol) {
                    return@firstNotNullOfOrNull "$symbol is a continuous futures stream: trade flow is read on a " +
                        "listed contract or a perpetual"
                }
                val first = day(group.minOf { it.earliestMs })
                val last = day(toMs - 1)
                val missing =
                    generateSequence(first) { it.plusDays(1) }
                        .takeWhile { !it.isAfter(last) }
                        .filterNot { store.has(symbol, kind, it) }
                        .toList()
                missing.firstOrNull()?.let {
                    "$symbol reads its ${kind.capability} but ${missing.size} day(s) of the run, its warmup and " +
                        "lookback are not stored (from $it): run `qkt fetch $symbol --${kind.flag} --from $first --to $last`"
                }
            }

    private fun day(ms: Long) = LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
}
