package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.strategy.Strategy
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1025 e2e — a bracket whose `BY` distance is an ATR expression backtests end to end:
 * the entry fill re-anchors the frozen distance to the fill price instead of dying on
 * an unsupported indicator expression.
 */
class AtrBracketBacktestTest {
    private fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    @Test
    fun `atr BY bracket fills and exits without crashing`() {
        val source = InMemoryMarketSource()
        val candles =
            (0 until 40).map { i ->
                val base = 100 + i
                Candle(
                    "BACKTEST:XAUUSD",
                    Money.of(base.toString()),
                    Money.of((base + 1).toString()),
                    Money.of((base - 1).toString()),
                    Money.of(base.toString()),
                    Money.of("1"),
                    i * 60_000L,
                    (i + 1) * 60_000L,
                )
            }
        source.seedBars("BACKTEST:XAUUSD", TimeWindow.ONE_MINUTE, candles)

        val strat =
            compile(
                """
                STRATEGY atr_bracket_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 0 AND POSITION.gold == 0
                  THEN BUY gold BRACKET { STOP LOSS BY atr(gold.candle, 14) * 2.0, TAKE PROFIT BY atr(gold.candle, 14) * 3.0 }
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("atr_bracket_e2e" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BACKTEST:XAUUSD"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(40 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.trades).isNotEmpty
    }
}
