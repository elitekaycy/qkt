package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.common.Money
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.parity.RegimePortfolioEdgeCaseFixtures.firstTs
import com.qkt.parity.RegimePortfolioEdgeCaseFixtures.gateHelpers
import com.qkt.parity.RegimePortfolioEdgeCaseFixtures.sym
import com.qkt.parity.RegimePortfolioEdgeCaseFixtures.unitRegistry
import com.qkt.risk.book.Allocation
import com.qkt.risk.book.AllocationMethod
import com.qkt.risk.book.BookRiskConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RegimeStackedChildrenEdgeCaseTest {
    @Test
    fun `stacked regime runs multiple children at full weight simultaneously`(
        @TempDir tmp: Path,
    ) {
        writeStackPortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        val backtest =
            Backtest(
                strategies =
                    compiled.children.map { child ->
                        child.strategyId to
                            com.qkt.backtest.GatedChild(
                                strategyId = child.strategyId,
                                inner = child.compiled,
                                hold = child.hold,
                                gateFor = gate.gateFor,
                                flattenSymbols = child.symbols,
                            )
                    },
                ticks =
                    listOf(
                        Tick(sym, Money.of("100"), firstTs),
                        Tick(sym, Money.of("300"), firstTs + 60_000L),
                        Tick(sym, Money.of("300"), firstTs + 120_000L),
                    ),
                candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                initialTimestamp = firstTs,
                startingBalance = BigDecimal("10000"),
                bookCapital = BigDecimal("10000"),
                instruments = unitRegistry(),
                gateFor = gate.gateFor,
                preCandle = gate.preCandle,
                regimeWeights = gate.regimeWeights,
                bookRiskConfig =
                    BookRiskConfig(
                        capital = BigDecimal("10000"),
                        allocation = Allocation(method = AllocationMethod.REGIME_WEIGHTED),
                    ),
            )

        val result = backtest.run()
        val tradesByChild = result.trades.groupBy { it.strategyId }

        // Both children are weighted 1.0 in the 'up' regime, so both enter on bar 2.
        assertThat(tradesByChild["book:trend"]).hasSize(1)
        assertThat(tradesByChild["book:meanrev"]).hasSize(1)
        assertThat(result.finalPositionsByStrategy["book:trend"]?.get(sym)?.quantity)
            .isGreaterThan(BigDecimal.ZERO)
        assertThat(result.finalPositionsByStrategy["book:meanrev"]?.get(sym)?.quantity)
            .isGreaterThan(BigDecimal.ZERO)
    }

    private fun writeStackPortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("trend.qkt"),
            """
            STRATEGY trend VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("meanrev.qkt"),
            """
            STRATEGY meanrev VERSION 1
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
            IMPORT 'trend.qkt'   AS trend
            IMPORT 'meanrev.qkt' AS meanrev
            REGIMES
                NAME r
                STATE up   WHEN btc.close > 200
                STATE down DEFAULT
            ALLOCATE
                METHOD regime_weighted
                up   -> trend 0.5, meanrev 0.5
                down -> trend 0.0, meanrev 0.0
            RULES
                RUN trend
                RUN meanrev
            """.trimIndent(),
        )
    }
}
