package com.qkt.instrument

import com.qkt.common.Money
import com.qkt.common.TradingCalendar

/**
 * [inner]'s instruments as a run read through [adjustment] sees them: a dividend before a split is per adjusted share
 * (divided by the same factor as the prices around it), so holding the adjusted quantity earns what the raw holding
 * did. Splits stay listed: the prices have them applied, and nothing books them. Symbols without a split are [inner]'s.
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
                raw.map { action ->
                    when (action) {
                        // Kept so the run refuses what live would around it ([com.qkt.risk.rules.SplitWindow]).
                        is CorporateAction.Split -> action
                        is CorporateAction.Dividend -> {
                            val factor = adjustment.factor(symbol, calendar.exDateStartMs(symbol, action.exDate))
                            action.copy(amount = action.amount.divide(factor, Money.CONTEXT))
                        }
                    }
                }
            }
        }

    override fun corporateActions(): CorporateActionSource? = actions
}
