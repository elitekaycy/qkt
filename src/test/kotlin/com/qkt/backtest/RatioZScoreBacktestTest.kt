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
 * End-to-end proof that the already-shipped `zscore` works as a cross-symbol ratio trigger
 * and replays deterministically. Two real instruments (XAUUSD + AUDUSD) on one timeframe feed
 * the real backtest data path (no mocks — a real [InMemoryMarketSource] of OHLC bars driving
 * the replay engine through bar-synthesis). The strategy enters when the gold/aud ratio z-score
 * pierces +2 and exits on reversion to 0.
 *
 * The bars are shaped so the ratio sits in a tight band for the warmup window, then spikes past
 * +2 sigma (a deterministic entry), then reverts (a deterministic exit). A non-zero trade count
 * proves the cross-symbol ratio fed the z-score through the candle path; two bit-identical runs
 * prove the construct is deterministic (the engine's core backtest=live invariant).
 */
class RatioZScoreBacktestTest {
    private val gold = "BACKTEST:XAUUSD"
    private val aud = "BACKTEST:AUDUSD"
    private val tf = TimeWindow.ONE_HOUR
    private val hour = 3_600_000L

    private val src =
        """
        STRATEGY ratioZscore VERSION 1
        DEFAULTS { SIZING = 0.01 TIF = GTC }
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 1h
            aud  = BACKTEST:AUDUSD EVERY 1h
            SYNCHRONIZE gold aud
        RULES
            WHEN zscore(gold.close / aud.close, 20) >= 2.0 AND POSITION.gold = 0
            THEN SELL gold
            WHEN zscore(gold.close / aud.close, 20) <= -2.0 AND POSITION.gold = 0
            THEN BUY gold
            WHEN POSITION.gold != 0 AND zscore(gold.close / aud.close, 20) <= 0.5
            THEN CLOSE gold
        """.trimIndent()

    private fun bar(
        symbol: String,
        close: String,
        i: Int,
    ): Candle {
        val start = i * hour
        val c = Money.of(close)
        return Candle(symbol, c, c, c, c, Money.of("1"), start, start + hour)
    }

    /**
     * Gold and aud closes shaped to drive a deterministic +2-sigma ratio spike then a reversion.
     * Bars 0..19: ratio jitters in a tight band (small non-zero stddev, no false trigger).
     * Bar 20: gold jumps, pushing gold/aud well past +2 sigma → SELL fires.
     * Bar 21: gold reverts toward the band → z-score collapses back inside ±2.
     */
    private fun seed(source: InMemoryMarketSource) {
        val goldBars = mutableListOf<Candle>()
        val audBars = mutableListOf<Candle>()
        for (i in 0 until 20) {
            // aud constant; gold alternates 2000.0 / 2000.5 → ratio ~3030 with a tiny stddev.
            val g = if (i % 2 == 0) "2000.0" else "2000.5"
            goldBars += bar(gold, g, i)
            audBars += bar(aud, "0.66", i)
        }
        // Spike: gold +30 against the same aud → ratio jumps far past +2 sigma.
        goldBars += bar(gold, "2030.0", 20)
        audBars += bar(aud, "0.66", 20)
        // Reversion: gold back into the band → ratio returns inside ±2.
        goldBars += bar(gold, "2000.0", 21)
        audBars += bar(aud, "0.66", 21)

        source.seedBars(gold, tf, goldBars)
        source.seedBars(aud, tf, audBars)
    }

    private fun compile(): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    private fun runOnce(): BacktestResult {
        val source = InMemoryMarketSource()
        seed(source)
        return Backtest
            .fromSource(
                strategies = listOf("ratioZscore" to compile()),
                source = source,
                request =
                    MarketRequest(
                        symbols = listOf(gold, aud),
                        from = Instant.ofEpochMilli(0L),
                        to = Instant.ofEpochMilli(22 * hour),
                    ),
                candleWindow = tf,
            ).run()
    }

    @Test
    fun `a cross-symbol ratio z-score entry fires and replays deterministically`() {
        val a = runOnce()
        // The +2-sigma spike must produce at least the SELL entry; a fill means the gold/aud
        // ratio reached the z-score through the synchronized candle path.
        assertThat(a.trades).isNotEmpty

        val b = runOnce()
        // Bit-identical trade tape across two runs — the backtest=live determinism invariant.
        assertThat(a.trades.map { it.trade }).isEqualTo(b.trades.map { it.trade })
        assertThat(a.global.equityCurve).isEqualTo(b.global.equityCurve)
    }
}
