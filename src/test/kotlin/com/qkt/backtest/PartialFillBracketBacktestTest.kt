package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An entry that fills in slices is protected for the whole fill, not for its last slice only. */
class PartialFillBracketBacktestTest {
    private val gold =
        InstrumentMeta(
            qktSymbol = "EXNESS:XAUUSD",
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.001"),
            digits = 3,
            tradeStopsLevelPoints = 0,
        )

    private fun tick(
        minute: Int,
        mid: String,
    ): Tick {
        val m = Money.of(mid)
        return Tick("EXNESS:XAUUSD", m, minute * 60_000L, bid = m - Money.of("0.05"), ask = m + Money.of("0.05"))
    }

    @Test
    fun `a bracket entry filled in slices is stopped out for its whole quantity`() {
        val strategy =
            compile(
                """
                STRATEGY partial VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close >= 2000 AND POSITION.gold = 0
                  THEN BUY gold SIZING 0.1 BRACKET { STOP_LOSS BY 3, TAKE_PROFIT BY 6 }
                """.trimIndent(),
            )
        val ticks = listOf(tick(0, "2000"), tick(1, "2000"), tick(2, "2000"), tick(3, "1990"), tick(4, "1990"))

        val result =
            Backtest(
                strategies = listOf("partial" to strategy),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
                instruments =
                    object : InstrumentRegistry {
                        override fun lookup(qktSymbol: String) = gold.takeIf { qktSymbol == it.qktSymbol }
                    },
                brokerKind = BrokerKind.MT5_SIM,
                executionConfig =
                    ExecutionSimulationConfig(
                        preset = ExecutionPreset.MT5_BASIC,
                        partialFillFraction = BigDecimal("0.5"),
                    ),
            ).run()

        val bought = result.trades.filter { it.trade.side == Side.BUY }.sumOf { it.trade.quantity }
        val sold = result.trades.filter { it.trade.side == Side.SELL }.sumOf { it.trade.quantity }
        assertThat(bought).isEqualByComparingTo("0.10")
        assertThat(sold).isEqualByComparingTo(bought)
    }
}
