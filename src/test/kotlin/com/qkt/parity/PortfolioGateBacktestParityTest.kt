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
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Verifies that portfolio `WHEN..RUN` regime gates are applied in backtest: a conditional child
 * is suppressed until its gate opens, while an `ALWAYS_RUN` child trades regardless. This is the
 * backtest half of live/backtest parity for regime-aware portfolios.
 */
class PortfolioGateBacktestParityTest {
    private val sym = "BACKTEST:BTCUSDT"
    private val firstTs = 1_700_000_000_000L

    /** One tick per minute so each tick closes its own 1m bar. */
    private fun ticks(): List<Tick> =
        listOf(
            Tick(sym, Money.of("100"), firstTs),
            Tick(sym, Money.of("101"), firstTs + 60_000L),
            Tick(sym, Money.of("102"), firstTs + 120_000L),
            Tick(sym, Money.of("103"), firstTs + 180_000L),
            Tick(sym, Money.of("104"), firstTs + 240_000L),
        )

    @Test
    fun `conditional child is suppressed until portfolio WHEN gate opens`(
        @TempDir tmp: Path,
    ) {
        writePortfolio(tmp)
        val compiled = PortfolioLoader.load(tmp.resolve("book.qkt"))

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

        val backtest =
            Backtest(
                strategies = compiled.children.map { it.strategyId to it.compiled },
                ticks = ticks(),
                candleWindow = com.qkt.candles.TimeWindow.ONE_MINUTE,
                initialTimestamp = firstTs,
                startingBalance = BigDecimal("10000"),
                instruments = unitRegistry(),
                gateFor = gateFor,
                preCandle = preCandle,
            )

        val result = backtest.run()
        val tradesByChild = result.trades.groupBy { it.strategyId }

        // The ALWAYS_RUN child enters on bar 1 close (fill at tick 2 price 101).
        // The conditional child is suppressed until RSI(2) becomes ready. RSI(2) needs three
        // rising closes (100, 101, 102); bar 3 closes at 102, so child_b enters on bar 3 close
        // (fill at tick 4 price 103).
        assertThat(tradesByChild["book:a"]).hasSize(1)
        assertThat(tradesByChild["book:a"]?.first()?.trade?.price).isEqualByComparingTo(Money.of("101"))

        assertThat(tradesByChild["book:b"]).hasSize(1)
        assertThat(tradesByChild["book:b"]?.first()?.trade?.price).isEqualByComparingTo(Money.of("103"))
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
            PORTFOLIO book VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            IMPORT 'a.qkt' AS a
            IMPORT 'b.qkt' AS b
            RULES
                RUN a
                WHEN rsi(btc.close, 2) > 50 RUN b
            """.trimIndent(),
        )
    }
}
