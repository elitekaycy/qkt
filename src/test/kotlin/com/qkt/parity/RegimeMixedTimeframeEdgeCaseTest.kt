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

class RegimeMixedTimeframeEdgeCaseTest {
    @Test
    fun `mixed child timeframes share account and book risk`(
        @TempDir tmp: Path,
    ) {
        writeMixedTimeframePortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        // 5m child gets a bar every 5 minutes; 1m child every minute. Regime flips on 1m bars.
        val ticks =
            (0..10).flatMap { m ->
                listOf(
                    Tick(sym, Money.of(if (m < 5) "100" else "300"), firstTs + m * 60_000L),
                )
            }

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
                ticks = ticks,
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
        assertThat(result.trades).isNotEmpty
        assertThat(result.bookRisk).isNotNull
        assertThat(result.perStrategy.keys).containsExactlyInAnyOrder("book:fast", "book:slow")
    }

    private fun writeMixedTimeframePortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("fast.qkt"),
            """
            STRATEGY fast VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN BUY x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("slow.qkt"),
            """
            STRATEGY slow VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 5m
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
            IMPORT 'fast.qkt' AS fast
            IMPORT 'slow.qkt' AS slow
            REGIMES
                NAME r
                STATE up   WHEN btc.close > 200
                STATE down DEFAULT
            ALLOCATE
                METHOD regime_weighted
                up   -> fast 0.5, slow 0.5
                down -> fast 0.0, slow 0.0
            RULES
                RUN fast
                RUN slow
            """.trimIndent(),
        )
    }
}
