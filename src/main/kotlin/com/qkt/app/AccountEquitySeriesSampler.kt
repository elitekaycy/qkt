package com.qkt.app

import com.qkt.common.Money
import com.qkt.dsl.ast.SeriesSymbols
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.marketdata.Tick
import com.qkt.risk.RiskState
import com.qkt.strategy.Strategy

/**
 * Feeds current account equity into the candle hub as a synthetic `ACCOUNT:EQUITY` tick, so a DSL
 * strategy can declare equity as a stream. A no-op unless some strategy declares that stream.
 */
internal class AccountEquitySeriesSampler(
    strategies: List<Pair<String, Strategy>>,
    private val candleHub: CandleHub,
    private val riskState: RiskState,
) {
    private val hasAccountEquitySeries: Boolean =
        strategies.any { (_, strategy) ->
            (strategy as? DslCompiledStrategy)
                ?.declaredStreams
                ?.values
                ?.any { it.broker == SeriesSymbols.BROKER && it.symbol == SeriesSymbols.ACCOUNT_EQUITY_SYMBOL }
                ?: false
        }

    /** Sample equity at [nowMs] into the hub when the series is declared. */
    fun sample(nowMs: Long) {
        if (!hasAccountEquitySeries) return
        candleHub.feed(
            Tick(
                symbol = "${SeriesSymbols.BROKER}:${SeriesSymbols.ACCOUNT_EQUITY_SYMBOL}",
                price = riskState.equityTracker.currentEquity(),
                timestamp = nowMs,
                volume = Money.ZERO,
            ),
        )
    }
}
