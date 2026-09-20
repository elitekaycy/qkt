package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioGate
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.parity.RegimeAdaptiveFixtures.firstTs
import com.qkt.parity.RegimeAdaptiveFixtures.sym
import com.qkt.parity.RegimeAdaptiveFixtures.ticks
import com.qkt.parity.RegimeAdaptiveFixtures.unitRegistry
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.AllocationMethod
import com.qkt.risk.book.BookRiskConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RegimeHoldChildBacktestParityTest {
    @Test
    fun `HOLD child keeps position when regime deactivates it`(
        @TempDir tmp: Path,
    ) {
        writeHoldPortfolio(tmp)
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

        val strategies =
            compiled.children.map { child ->
                child.strategyId to
                    com.qkt.backtest.GatedChild(
                        strategyId = child.strategyId,
                        inner = child.compiled,
                        hold = child.hold,
                        gateFor = gateFor,
                        flattenSymbols = child.symbols,
                    )
            }

        val backtest =
            Backtest(
                strategies = strategies,
                ticks =
                    listOf(
                        Tick(sym, Money.of("100"), firstTs),
                        Tick(sym, Money.of("100"), firstTs + 60_000L),
                        Tick(sym, Money.of("300"), firstTs + 120_000L),
                        Tick(sym, Money.of("100"), firstTs + 180_000L),
                        Tick(sym, Money.of("100"), firstTs + 240_000L),
                    ),
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

        // low -> b enters; high -> b flattens and a enters; low -> a holds, b re-enters.
        assertThat(tradesByChild["book:b"]).hasSize(3)
        assertThat(tradesByChild["book:a"]).hasSize(1)

        val aPos = result.finalPositionsByStrategy["book:a"]?.get(sym)?.quantity ?: BigDecimal.ZERO
        val bPos = result.finalPositionsByStrategy["book:b"]?.get(sym)?.quantity ?: BigDecimal.ZERO
        assertThat(aPos).isGreaterThan(BigDecimal.ZERO)
        assertThat(bPos).isGreaterThan(BigDecimal.ZERO)
    }

    private fun writeHoldPortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("a.qkt"),
            """
            STRATEGY child_a VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
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
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("book.qkt"),
            """
            PORTFOLIO book VERSION 1 CAPITAL 10000
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'a.qkt' AS a HOLD
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
                WHEN btc.close > 200 RUN a
                WHEN btc.close <= 200 RUN b
            """.trimIndent(),
        )
    }
}
