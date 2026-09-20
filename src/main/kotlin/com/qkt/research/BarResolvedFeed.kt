package com.qkt.research

import com.qkt.app.IntrabarFill
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.math.BigDecimal

/**
 * A [TickFeed] that drives a `--bars` replay but resolves fills on real ticks where one is possible,
 * staying byte-identical to a full-tick replay — including across symbols.
 *
 * Each symbol is driven by its own [SymbolFeed] (bars + lazy real slices). The merge emits the
 * earliest pending tick across symbols, so the global tick order matches a full-tick `MergingTickFeed`.
 *
 * Per symbol, per bar: emit the bar's **opening tick** (real), then — once the engine has ingested it
 * — decide the remainder via [IntrabarFill]:
 *  - `SYNTHETIC` -> the synthetic `low -> high -> close` (no fill can occur);
 *  - `EXTREMES`  -> only the bar's new-extreme ticks plus the close (the first crossing of any static
 *    level is necessarily a new-extreme tick, and the price extremes carry the candle high/low and the
 *    mark-to-market excursion — so this is byte-identical while feeding far fewer ticks);
 *  - `ALL_TICKS` -> the full real slice (a trailing/composite order or a time-based exit could fire on
 *    a tick the extreme filter would skip).
 *
 * After the last completed bar, exact ticks through [replayEndMs] are emitted unchanged so final
 * marks and fills match full-tick replay even when the requested range ends inside a partial bar.
 */
class BarResolvedFeed(
    perSymbolBars: Map<String, Sequence<Candle>>,
    sliceProvider: (symbol: String, fromMs: Long, toMs: Long) -> Sequence<Tick>,
    intrabarFill: (symbol: String, low: BigDecimal, high: BigDecimal, maxHalfSpread: BigDecimal) -> IntrabarFill,
    replayEndMs: Long? = null,
) : TickFeed {
    private val subs =
        perSymbolBars.entries.map { (sym, bars) ->
            SymbolFeed(sym, bars, sliceProvider, intrabarFill, replayEndMs)
        }

    override fun next(): Tick? {
        // The tick emitted last cycle is now ingested; let whichever symbol just emitted its opening
        // tick resolve its bar before we compare frontiers.
        for (s in subs) s.settle()
        // Manual min scan (strict `<` keeps the earlier-sub tie-break minByOrNull had); the
        // filter+minByOrNull pair allocated a list plus boxed comparisons per emitted tick.
        var pick: SymbolFeed? = null
        var pickTs = Long.MAX_VALUE
        for (i in subs.indices) {
            val ts = subs[i].peek()?.timestamp ?: continue
            if (ts < pickTs) {
                pick = subs[i]
                pickTs = ts
            }
        }
        return pick?.pop()
    }
}
