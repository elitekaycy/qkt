package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.strategy.Strategy
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end proof of the market-neutral BASKET spread: a `SELL gold ; BUY antipodean`
 * entry fans out to one real order per constituent (XAUUSD, AUDUSD, NZDUSD), `CLOSE
 * antipodean` flattens both constituents, and the whole construct replays bit-identically.
 *
 * Three real instruments on one timeframe feed the real backtest data path (no mocks — a
 * real [InMemoryMarketSource] of OHLC bars through the replay engine). The constituents are
 * held flat so the basket index sits at its base while the gold/basket ratio is shaped to
 * jitter in a tight band, spike past +2 sigma (a deterministic entry), then revert (a
 * deterministic exit). Seeing fills on all three symbols proves the fan-out; two identical
 * runs prove the engine's backtest=live determinism invariant.
 */
class BasketSpreadBacktestTest {
    private val gold = "BACKTEST:XAUUSD"
    private val aud = "BACKTEST:AUDUSD"
    private val nzd = "BACKTEST:NZDUSD"
    private val tf = TimeWindow.ONE_HOUR
    private val hour = 3_600_000L

    private val src =
        """
        STRATEGY basketSpread VERSION 1
        DEFAULTS { SIZING = 0.01 }
        SYMBOLS
            gold = BACKTEST:XAUUSD EVERY 1h
            aud  = BACKTEST:AUDUSD EVERY 1h
            nzd  = BACKTEST:NZDUSD EVERY 1h
            antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
            SYNCHRONIZE gold antipodean
        RULES
            WHEN zscore(gold.close / antipodean.close, 20) >= 2.0 AND POSITION.gold = 0
            THEN SELL gold ; BUY antipodean SIZING 5000 USD
            WHEN POSITION.gold != 0 AND zscore(gold.close / antipodean.close, 20) <= 0.5
            THEN CLOSE gold ; CLOSE antipodean
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
     * Constituents flat (basket index pinned at its base); gold jitters in a tight band
     * through warmup, then spikes past +2 sigma (entry), then reverts inside the band (exit).
     * Windows 0..23 warm the period-20 z-score over the basket-aligned ratio; the post-spike
     * reverted windows give the exit rule a bar to fire on and flatten every leg.
     */
    private fun seed(source: InMemoryMarketSource) {
        val goldBars = mutableListOf<Candle>()
        val audBars = mutableListOf<Candle>()
        val nzdBars = mutableListOf<Candle>()
        for (i in 0..23) {
            val g = if (i % 2 == 0) "2000.0" else "2000.5"
            goldBars += bar(gold, g, i)
            audBars += bar(aud, "0.66", i)
            nzdBars += bar(nzd, "0.60", i)
        }
        // Spike: gold +30 against flat constituents → gold/basket ratio jumps far past +2 sigma.
        goldBars += bar(gold, "2030.0", 24)
        audBars += bar(aud, "0.66", 24)
        nzdBars += bar(nzd, "0.60", 24)
        // Reversion + tail: gold back in the band → z-score collapses → the exit flattens all.
        for (i in 25..27) {
            goldBars += bar(gold, "2000.0", i)
            audBars += bar(aud, "0.66", i)
            nzdBars += bar(nzd, "0.60", i)
        }

        source.seedBars(gold, tf, goldBars)
        source.seedBars(aud, tf, audBars)
        source.seedBars(nzd, tf, nzdBars)
    }

    private fun compile(strategySrc: String): Strategy =
        when (val r = Dsl.parse(strategySrc)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    // Notional sizing on the basket fan-out needs each constituent's contract size; the
    // default backtest registry knows none of these symbols. Gold is metal-shaped (100 oz/lot)
    // and the FX constituents are 100,000/lot — realistic sizes keep each leg's notional under
    // the engine's default order cap.
    private fun instruments(): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = if (qktSymbol == gold) Money.of("100") else Money.of("100000"),
                    volumeStep = Money.of("0.01"),
                    volumeMin = Money.of("0.01"),
                    volumeMax = null,
                    pointSize = Money.of("0.00001"),
                    digits = 5,
                    tradeStopsLevelPoints = 0,
                )
        }

    private fun runOnce(): BacktestResult {
        val source = InMemoryMarketSource()
        seed(source)
        return Backtest
            .fromSource(
                strategies = listOf("basketSpread" to compile(src)),
                source = source,
                request =
                    MarketRequest(
                        symbols = listOf(gold, aud, nzd),
                        from = Instant.ofEpochMilli(0L),
                        to = Instant.ofEpochMilli(28 * hour),
                    ),
                candleWindow = tf,
                instruments = instruments(),
            ).run()
    }

    @Test
    fun `a basket spread entry fans out to all constituents and replays deterministically`() {
        val a = runOnce()
        assertThat(a.trades).isNotEmpty

        // Entry fans out: the gold leg plus one real order per constituent. All three trade.
        val tradedSymbols = a.trades.map { it.trade.symbol }.toSet()
        assertThat(tradedSymbols).containsExactlyInAnyOrder(gold, aud, nzd)

        // CLOSE fan-out flattens every leg on exit — gold and both constituents end flat.
        val perSymbol = a.trades.groupingBy { it.trade.symbol }.eachCount()
        assertThat(perSymbol[gold]).isGreaterThanOrEqualTo(2)
        assertThat(perSymbol[aud]).isGreaterThanOrEqualTo(2)
        assertThat(perSymbol[nzd]).isGreaterThanOrEqualTo(2)
        assertThat(a.finalPositions.values.all { it.quantity.signum() == 0 }).isTrue

        val b = runOnce()
        // Bit-identical trade tape and equity curve across two runs — backtest=live determinism.
        assertThat(a.trades.map { it.trade }).isEqualTo(b.trades.map { it.trade })
        assertThat(a.global.equityCurve).isEqualTo(b.global.equityCurve)
    }

    @Test
    fun `the shipped basket-spread example compiles`() {
        val exampleSrc =
            Path
                .of("examples", "basket-spread", "basket-spread.qkt")
                .toFile()
                .readText()
        // Parses and compiles end to end (fan-out, basket position view, rule-driven exits).
        compile(exampleSrc)
    }
}
