package com.qkt.instrument

import com.qkt.common.Money
import com.qkt.common.TradingCalendar

/**
 * [inner]'s instruments as a run read through [adjustment] sees them: a split it has already applied to the prices
 * is gone, and a dividend before a split is per adjusted share (divided by the same factor as the prices around
 * it), so holding the adjusted quantity earns what the raw holding did. Symbols without a split are [inner]'s.
 */
class SplitAdjustedActions(
    private val inner: InstrumentRegistry,
    private val adjustment: SplitAdjustment,
    private val calendar: TradingCalendar,
) : InstrumentRegistry by inner {
    private val actions =
        inner.corporateActions()?.let { source ->
            CorporateActionSource { symbol ->
                val raw = source.actions(symbol)
                if (symbol !in adjustment.symbols) return@CorporateActionSource raw
                raw.mapNotNull { action ->
                    val atMs = calendar.exDateStartMs(symbol, action.exDate)
                    when (action) {
                        // A split after the run's end is no part of its series, so stays an action.
                        is CorporateAction.Split -> action.takeUnless { adjustment.applies(symbol, atMs) }
                        is CorporateAction.Dividend ->
                            action.copy(amount = action.amount.divide(adjustment.factor(symbol, atMs), Money.CONTEXT))
                    }
                }
            }
        }

    override fun corporateActions(): CorporateActionSource? = actions
}
