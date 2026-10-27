package com.qkt.instrument

import com.qkt.common.TradingCalendar
import java.math.BigDecimal

/**
 * How a run ending at its `asOfMs` reads a share that split during it: a price stamped before a split's ex-date
 * session start is divided by that split's ratio and by every later one's up to the run's end, and a volume or a
 * per-share dividend scaled the same way, so the whole run reads in the units the share trades in at its end. A
 * 10-for-1 split reads as the continuation it is rather than a 90% fall, and a run with no split before its end
 * reads raw prices. Positions are never restated: there is no split left inside the series to restate them at.
 */
class SplitAdjustment private constructor(
    private val steps: Map<String, List<Step>>,
) {
    /** Prices stamped before [untilMs] are divided by [factor], every split from [untilMs] to the run's end included. */
    private class Step(
        val untilMs: Long,
        val factor: BigDecimal,
    )

    /** The symbols with a split before the run's end. */
    val symbols: Set<String> get() = steps.keys

    fun isEmpty(): Boolean = steps.isEmpty()

    /** What [symbol]'s per-share values stamped at [timeMs] are divided by: one from its last split on. */
    fun factor(
        symbol: String,
        timeMs: Long,
    ): BigDecimal = steps[symbol]?.firstOrNull { timeMs < it.untilMs }?.factor ?: BigDecimal.ONE

    companion object {
        val NONE = SplitAdjustment(emptyMap())

        /**
         * The adjustment of [symbols] by the splits in [actions] whose ex-date session on [calendar] starts before
         * [asOfMs]; splits after the run's end change nothing in it.
         */
        fun of(
            actions: CorporateActionSource?,
            symbols: Collection<String>,
            calendar: TradingCalendar,
            asOfMs: Long,
        ): SplitAdjustment {
            if (actions == null) return NONE
            val steps =
                symbols.distinct().mapNotNull { symbol ->
                    val splits =
                        actions
                            .actions(symbol)
                            .filterIsInstance<CorporateAction.Split>()
                            .map { calendar.exDateStartMs(symbol, it.exDate) to it.ratio }
                            .filter { (at, _) -> at < asOfMs }
                            .sortedBy { it.first }
                    if (splits.isEmpty()) return@mapNotNull null
                    // Cumulative from the latest back: before the first split, every split's ratio applies.
                    var factor = BigDecimal.ONE
                    symbol to
                        splits
                            .asReversed()
                            .map { (at, ratio) ->
                                factor = factor.multiply(ratio)
                                Step(at, factor)
                            }.asReversed()
                }
            return if (steps.isEmpty()) NONE else SplitAdjustment(steps.toMap())
        }
    }
}
