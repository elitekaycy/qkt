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

class RegimeShortFlattenEdgeCaseTest {
    @Test
    fun `short child flattens with forced buy when regime deactivates it`(
        @TempDir tmp: Path,
    ) {
        writeShortPortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))
        val gate = gateHelpers(compiled)

        // Three 1m bars. Bar 1 close=100 (low regime -> shortChild active) sells short at next open.
        // Bar 2 close=300 (high regime -> shortChild deactivated) flattens the short.
        // Bar 3 close=300 keeps longChild active; no new short re-entry because gate is closed.
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
        val shortTrades = result.trades.filter { it.strategyId == "book:shortChild" }

        // One short entry (SELL) and one forced flatten (BUY) from the same child.
        assertThat(shortTrades).hasSize(2)
        assertThat(shortTrades[0].trade.side).isEqualTo(Side.SELL)
        assertThat(shortTrades[1].trade.side).isEqualTo(Side.BUY)
        val shortFinalQty =
            result.finalPositionsByStrategy["book:shortChild"]?.get(sym)?.quantity ?: BigDecimal.ZERO
        assertThat(shortFinalQty).isEqualByComparingTo(BigDecimal.ZERO)
    }

    private fun writeShortPortfolio(tmp: Path) {
        Files.writeString(
            tmp.resolve("shortChild.qkt"),
            """
            STRATEGY short_child VERSION 1
            SYMBOLS
                x = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN x.close > 0 AND POSITION.x = 0
                THEN SELL x SIZING 1
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("longChild.qkt"),
            """
            STRATEGY long_child VERSION 1
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
            IMPORT 'shortChild.qkt' AS shortChild
            IMPORT 'longChild.qkt'  AS longChild
            REGIMES
                NAME r
                STATE high WHEN btc.close > 200
                STATE low DEFAULT
            ALLOCATE
                METHOD regime_weighted
                high -> longChild 1.0
                low  -> shortChild 1.0
            RULES
                WHEN btc.close > 200 RUN longChild
                WHEN btc.close <= 200 RUN shortChild
            """.trimIndent(),
        )
    }
}
