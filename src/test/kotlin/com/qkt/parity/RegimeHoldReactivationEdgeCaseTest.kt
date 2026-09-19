package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.common.Money
import com.qkt.common.Side
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

class RegimeHoldReactivationEdgeCaseTest {
    @Test
    fun `HOLD child survives deactivation and reactivates without duplicate flatten`(
        @TempDir tmp: Path,
    ) {
        writeHoldReactivationPortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        // low -> b enters; high -> b holds (no flatten), a enters; low -> b still holds, a flattens.
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
                        Tick(sym, Money.of("100"), firstTs + 60_000L),
                        Tick(sym, Money.of("300"), firstTs + 120_000L),
                        Tick(sym, Money.of("300"), firstTs + 180_000L),
                        Tick(sym, Money.of("100"), firstTs + 240_000L),
                        Tick(sym, Money.of("100"), firstTs + 300_000L),
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
        val aTrades = result.trades.filter { it.strategyId == "book:a" }
        val bTrades = result.trades.filter { it.strategyId == "book:b" }

        // a is non-HOLD: enters in high, flattens in low.
        assertThat(aTrades).hasSize(2)
        assertThat(aTrades[0].trade.side).isEqualTo(Side.BUY)
        assertThat(aTrades[1].trade.side).isEqualTo(Side.SELL)

        // b is HOLD: only one entry (in low) and no flatten trades across regime switches.
        assertThat(bTrades).hasSize(1)
        assertThat(bTrades[0].trade.side).isEqualTo(Side.BUY)
        assertThat(result.finalPositionsByStrategy["book:b"]?.get(sym)?.quantity)
            .isGreaterThan(BigDecimal.ZERO)
    }

    private fun writeHoldReactivationPortfolio(tmp: Path) {
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
            IMPORT 'a.qkt' AS a
            IMPORT 'b.qkt' AS b HOLD
            REGIMES
                NAME r
                STATE high WHEN btc.close > 200
                STATE low DEFAULT
            ALLOCATE
                METHOD regime_weighted
                high -> a 1.0
                low  -> b 1.0
            RULES
                WHEN btc.close > 200 RUN a
                WHEN btc.close <= 200 RUN b
            """.trimIndent(),
        )
    }
}
