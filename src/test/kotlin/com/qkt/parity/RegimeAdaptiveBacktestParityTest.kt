package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.AllocationMethod
import com.qkt.risk.book.BookRiskConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Verifies that portfolio `REGIMES` + `ALLOCATE METHOD regime_weighted` actually scales child orders
 * in a backtest. One regime gives full weight to child A and zero to child B; the default regime does
 * the opposite. We assert that each child only trades while its regime is active.
 */
class RegimeAdaptiveBacktestParityTest {
    private val sym = "BACKTEST:BTCUSDT"
    private val firstTs = 1_700_000_000_000L

    /** Three ticks: first closes the default-regime bar, second closes the high-regime bar, third fills it. */
    private fun ticks(): List<Tick> =
        listOf(
            Tick(sym, Money.of("100"), firstTs),
            Tick(sym, Money.of("300"), firstTs + 60_000L),
            Tick(sym, Money.of("300"), firstTs + 120_000L),
        )

    @Test
    fun `regime weighted allocation switches child order scaling`(
        @TempDir tmp: Path,
    ) {
        writePortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
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
        val gateFor: (String) -> Boolean = { strategyId ->
            portfolioGate.currentState().activeByAlias[strategyId.substringAfter(":")] == true
        }
        val preCandle: (com.qkt.marketdata.Candle) -> Unit = { candle ->
            portfolioGate.onCandle(candle)
        }
        val regimeWeights: () -> Map<String, BigDecimal> = {
            portfolioGate.currentState().weightByAlias.mapKeys { (alias, _) ->
                aliasToStrategyId[alias] ?: alias
            }
        }

        val bookRiskConfig =
            BookRiskConfig(
                capital = BigDecimal("10000"),
                allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED),
            )

        val backtest =
            Backtest(
                strategies = compiled.children.map { it.strategyId to it.compiled },
                ticks = ticks(),
                candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                initialTimestamp = firstTs,
                startingBalance = BigDecimal("10000"),
                bookCapital = BigDecimal("10000"),
                instruments = unitRegistry(),
                gateFor = gateFor,
                preCandle = preCandle,
                regimeWeights = regimeWeights,
                bookRiskConfig = bookRiskConfig,
            )

        val result = backtest.run()
        val tradesByChild = result.trades.groupBy { it.strategyId }

        // Bar 1 closes at 100 -> default regime -> b weight 1.0, a weight 0.0 -> b enters, fill at tick 2.
        val bTrades = tradesByChild["book:b"]
        assertThat(bTrades).hasSize(1)
        assertThat(bTrades?.first()?.trade?.price).isEqualByComparingTo(Money.of("300"))
        assertThat(bTrades?.first()?.trade?.timestamp).isEqualTo(firstTs + 60_000L)

        // Bar 2 closes at 300 -> high regime -> a weight 1.0, b weight 0.0 -> a enters, fill at tick 3.
        val aTrades = tradesByChild["book:a"]
        assertThat(aTrades).hasSize(1)
        assertThat(aTrades?.first()?.trade?.price).isEqualByComparingTo(Money.of("300"))
        assertThat(aTrades?.first()?.trade?.timestamp).isEqualTo(firstTs + 120_000L)
    }

    private fun unitRegistry(): InstrumentRegistry =
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

    private fun writePortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("a.qkt"),
            """
            STRATEGY child_a VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1 PCT RISK BRACKET { STOP LOSS PCT 10, TAKE PROFIT AT 1000 }
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("b.qkt"),
            """
            STRATEGY child_b VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1 PCT RISK BRACKET { STOP LOSS PCT 10, TAKE PROFIT AT 1000 }
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("book.qkt"),
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'a.qkt' AS a
            IMPORT 'b.qkt' AS b
            REGIMES
                NAME r
                STATE high WHEN btc.close > 200
                STATE low DEFAULT
            ALLOCATE
                METHOD regime_weighted
                high -> a 1.0
                low -> b 1.0
            RULES
                RUN a
                RUN b
            """.trimIndent(),
        )
    }
}
