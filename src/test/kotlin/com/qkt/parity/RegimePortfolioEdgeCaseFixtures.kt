package com.qkt.parity

import com.qkt.common.TradingCalendar
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

    /** The gate helpers a portfolio backtest runs with: the production [com.qkt.cli.PortfolioBacktestWiring] itself. */
    fun gateHelpers(compiled: com.qkt.dsl.portfolio.PortfolioCompiled): GateHelpers {
        val wiring =
            com.qkt.cli.PortfolioBacktestWiring(
                compiled,
                java.time.Instant.ofEpochMilli(firstTs),
                TradingCalendar.crypto(),
            )
        return GateHelpers(wiring.gateFor, wiring.preCandle, wiring.regimeWeights)
    }

    data class GateHelpers(
        val gateFor: (String) -> Boolean,
        val preCandle: (com.qkt.marketdata.Candle) -> Unit,
        val regimeWeights: () -> Map<String, BigDecimal>,
    )
}
