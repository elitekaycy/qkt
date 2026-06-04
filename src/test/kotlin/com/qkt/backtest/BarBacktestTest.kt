package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.TimeRange
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #214 — a bars-only venue (crypto) must backtest by synthesizing ticks from its OHLC bars.
 * Proves: (1) a source advertising only BARS produces trades; (2) when real ticks exist they
 * are preferred over bar-synthesis; (3) the synthesized intra-bar low fires a stop the close
 * alone never would (the pessimistic O->L->H->C ordering).
 */
class BarBacktestTest {
    private fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    private fun candle(
        o: String,
        h: String,
        l: String,
        c: String,
        start: Long,
    ): Candle =
        Candle(
            "BYBIT_SPOT:BTCUSDT",
            Money.of(o),
            Money.of(h),
            Money.of(l),
            Money.of(c),
            Money.of("1"),
            start,
            start + 60_000L,
        )

    @Test
    fun `a bars-only source synthesizes ticks and produces trades`() {
        val source = InMemoryMarketSource()
        val candles = (0 until 4).map { i -> candle("100", "110", "90", "105", i * 60_000L) }
        source.seedBars("BYBIT_SPOT:BTCUSDT", TimeWindow.ONE_MINUTE, candles)

        val strat =
            compile(
                """
                STRATEGY bars_only VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BYBIT_SPOT:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 0
                  THEN BUY btc
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("bars_only" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(4 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.trades).isNotEmpty
    }

    @Test
    fun `real ticks are preferred over bar synthesis`() {
        // The source has both TICKS and BARS, but only the tick carries price 999 — no candle does.
        // If the feed drove off the bars, the strategy would never see 999.
        val source =
            object : MarketSource {
                override val name = "tick-and-bar"
                override val capabilities =
                    setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

                override fun supports(symbol: String) = true

                override fun ticks(
                    symbol: String,
                    range: TimeRange,
                ): Sequence<Tick> = sequenceOf(Tick("BYBIT_SPOT:BTCUSDT", Money.of("999"), 1_000L))

                override fun bars(
                    symbol: String,
                    window: TimeWindow,
                    range: TimeRange,
                ): Sequence<Candle> = sequenceOf(candle("1", "1", "1", "1", 0L))
            }

        val seen = mutableListOf<java.math.BigDecimal>()
        val recorder =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seen.add(tick.price)
                }
            }

        Backtest
            .fromSource(
                strategies = listOf("recorder" to recorder),
                source = source,
                request =
                    MarketRequest(
                        symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                        from = Instant.ofEpochMilli(0L),
                        to = Instant.ofEpochMilli(60_000L),
                    ),
            ).run()

        assertThat(seen).anyMatch { it.compareTo(Money.of("999")) == 0 }
        assertThat(seen).noneMatch { it.compareTo(Money.of("1")) == 0 }
    }

    @Test
    fun `a synthesized intra-bar low fires a stop the close alone would not`() {
        // Entry bar closes at 105; protective stop at 95, take-profit parked at 200 (never hit).
        // The next bar's CLOSE is 100 (above the stop), but its LOW is 80. Only because the low is
        // replayed (O->L->H->C) does the stop fire.
        val source = InMemoryMarketSource()
        val candles =
            listOf(
                candle("100", "110", "99", "105", 0L),
                candle("105", "106", "80", "100", 60_000L),
            )
        source.seedBars("BYBIT_SPOT:BTCUSDT", TimeWindow.ONE_MINUTE, candles)

        val strat =
            compile(
                """
                STRATEGY stop_fidelity VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BYBIT_SPOT:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 0 AND POSITION.btc = 0
                  THEN BUY btc BRACKET { STOP LOSS AT 95, TAKE PROFIT AT 200 }
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("stop_fidelity" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(2 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.trades.map { it.trade.side }).contains(Side.SELL)
    }
}
