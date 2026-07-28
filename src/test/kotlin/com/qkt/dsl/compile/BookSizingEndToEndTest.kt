package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * End-to-end confirmation that `SIZING = N PCT RISK OF BOOK` parses, compiles, and sizes
 * off the shared book (CAPITAL + realized PnL of every child) through `Backtest` — the
 * same definition the live `PortfolioDeployer` binds, so this doubles as the parity pin
 * for the backtest side. Complements [SizingCompilerTest] (unit) and
 * [com.qkt.dsl.parse.ParserSizingTest] (surface).
 */
class BookSizingEndToEndTest {
    private val unitContracts =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal("0.001"),
                    volumeMin = BigDecimal("0.001"),
                    volumeMax = null,
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private fun compile(src: String) =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error(
                    "parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}",
                )
        }

    private fun entrant(
        name: String,
        entryClose: String,
        slPct: String,
    ) = compile(
        """
        STRATEGY $name VERSION 1
        DEFAULTS { SIZING = 1.0 PCT RISK OF BOOK TIF = GTC }
        SYMBOLS
          btc = BACKTEST:BTCUSDT EVERY 1m
        RULES
          WHEN btc.close = $entryClose AND POSITION.btc = 0
          THEN BUY btc BRACKET { STOP LOSS PCT $slPct, TAKE PROFIT AT 1000000 }

          WHEN POSITION.btc != 0 AND btc.close = 110
          THEN CLOSE btc
        """.trimIndent(),
    )

    @Test
    fun `children size off book capital and re-size after another child's realized pnl`() {
        // Book CAPITAL 10000. Market orders fill on the tick after the signalling bar close,
        // so each signal price repeats once to pin the fill at that price.
        //   close 100: child a enters — 1% of 10000 = 100 risk; SL 10% of 100 = 10 -> qty 10.
        //   close 110: a closes, fill 110: realized +10 x 10 = +100 -> book 10100.
        //   close 50:  child b enters — 1% of 10100 = 101 risk; SL 10% of 50 = 5 -> qty 20.2.
        // b sizing off its own (empty) equity or off unchanged CAPITAL would give 20, not 20.2.
        val sample = ticks(listOf("100", "100", "110", "110", "50", "50", "50", "50"))
        val result =
            Backtest(
                strategies =
                    listOf(
                        "book:a" to entrant("a", entryClose = "100", slPct = "10"),
                        "book:b" to entrant("b", entryClose = "50", slPct = "10"),
                    ),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
                startingBalance = BigDecimal("10000"),
                bookCapital = BigDecimal("10000"),
                instruments = unitContracts,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }.sortedBy { it.trade.timestamp }
        assertThat(buys).hasSize(2)
        assertThat(buys[0].trade.quantity).isEqualByComparingTo("10")
        assertThat(buys[1].trade.quantity).isEqualByComparingTo("20.2")
    }

    @Test
    fun `an OF BOOK strategy without a book fails at deploy not at first signal`() {
        assertThatThrownBy {
            Backtest(
                strategies = listOf("solo" to entrant("solo", entryClose = "100", slPct = "10")),
                ticks = ticks(listOf("100", "101")),
                candleWindow = TimeWindow.ONE_MINUTE,
                startingBalance = BigDecimal("10000"),
                instruments = unitContracts,
            ).run()
        }.hasMessageContaining("RISK OF BOOK")
            .hasMessageContaining("PORTFOLIO")
    }
}
