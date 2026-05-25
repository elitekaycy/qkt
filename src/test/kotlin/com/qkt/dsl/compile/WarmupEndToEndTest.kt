package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupEndToEndTest {
    private fun ticks(count: Int): List<Tick> =
        (1..count).map { i ->
            Tick(
                symbol = "BACKTEST:BTCUSDT",
                price = Money.of((100 + i).toString()),
                timestamp = i * 60_000L,
            )
        }

    private fun compile(src: String) = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    @Test
    fun `WARMUP suppresses rule firings until the configured bar count`() {
        val withWarmup =
            compile(
                """
                STRATEGY warmup_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m WARMUP 5 BARS
                RULES
                  WHEN btc.close IS NOT NULL AND POSITION.btc = 0
                  THEN BUY btc BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 1 }
                """.trimIndent(),
            )

        val withoutWarmup =
            compile(
                """
                STRATEGY no_warmup VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close IS NOT NULL AND POSITION.btc = 0
                  THEN BUY btc BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 1 }
                """.trimIndent(),
            )

        val sample = ticks(20)

        val resultWithWarmup =
            Backtest(
                strategies = listOf("warmup_e2e" to withWarmup),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val resultWithoutWarmup =
            Backtest(
                strategies = listOf("no_warmup" to withoutWarmup),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(resultWithWarmup.trades.size)
            .isLessThan(resultWithoutWarmup.trades.size)
            .isGreaterThan(0)
    }

    @Test
    fun `FLATTEN action closes open positions`() {
        val strat =
            compile(
                """
                STRATEGY flatten_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 100 AND POSITION.btc = 0
                  THEN BUY btc

                  WHEN POSITION.btc != 0
                  THEN FLATTEN
                """.trimIndent(),
            )

        val result =
            Backtest(
                strategies = listOf("flatten_e2e" to strat),
                ticks = ticks(10),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(result.trades).hasSizeGreaterThanOrEqualTo(2)
        val finalQty =
            result.finalPositions["BACKTEST:BTCUSDT"]?.quantity
                ?: BigDecimal.ZERO
        assertThat(finalQty.signum()).isEqualTo(0)
    }
}
