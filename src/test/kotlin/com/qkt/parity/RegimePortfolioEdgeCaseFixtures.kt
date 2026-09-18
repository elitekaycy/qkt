package com.qkt.parity

import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * Edge-case and combination tests for regime-adaptive portfolio backtests.
 *
 * These scenarios stress the parity path introduced in [RegimeAdaptiveBacktestParityTest]:
 * short-side flatten, simultaneous multi-child ("stack") regimes, mixed child timeframes,
 * HOLD reactivation, and config-driven book-risk wiring.
 */
internal object RegimePortfolioEdgeCaseFixtures {
    val sym = "BACKTEST:BTCUSDT"
    val firstTs = 1_700_000_000_000L

    fun unitRegistry(): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = BigDecimal("1000"),
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    /** Builds the gate helpers exactly as [BacktestContext.buildPortfolio] does. */
    fun gateHelpers(compiled: com.qkt.dsl.portfolio.PortfolioCompiled): GateHelpers {
        val aliasToStrategyId = compiled.children.associate { it.alias to it.strategyId }
        val portfolioGate =
            PortfolioGate(
                ast = compiled.ast,
                clock = FixedClock(time = firstTs),
                calendar = TradingCalendar.crypto(),
            ).also {
                it.prepare()
                it.initialState()
            }
        return GateHelpers(
            gateFor = { strategyId ->
                portfolioGate.currentState().activeByAlias[strategyId.substringAfter(":")] == true
            },
            preCandle = { candle -> portfolioGate.onCandle(candle) },
            regimeWeights = {
                portfolioGate.currentState().weightByAlias.mapKeys { (alias, _) ->
                    aliasToStrategyId[alias] ?: alias
                }
            },
        )
    }

    data class GateHelpers(
        val gateFor: (String) -> Boolean,
        val preCandle: (com.qkt.marketdata.Candle) -> Unit,
        val regimeWeights: () -> Map<String, BigDecimal>,
    )
}
