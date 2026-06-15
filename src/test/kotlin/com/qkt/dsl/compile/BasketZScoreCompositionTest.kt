package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #457 Task B7 — `zscore` of a `gold / basket` ratio composes with the BASKET primitive.
 * The basket alias satisfies the expression-fed primary-alias gate, the z-score binds with
 * `gold` primary, and a `SYNCHRONIZE gold antipodean` group makes the ratio sample the
 * same-window composite. A deterministic ratio spike past +2 sigma fires; warmup does not.
 */
class BasketZScoreCompositionTest {
    private val goldSym = "EXNESS:XAUUSD"
    private val audSym = "EXNESS:AUDUSD"
    private val nzdSym = "EXNESS:NZDUSD"
    private val hour = 3_600_000L

    private val src =
        """
        STRATEGY pairs VERSION 1
        DEFAULTS { SIZING = 0.1 TIF = GTC }
        SYMBOLS
            gold = EXNESS:XAUUSD EVERY 1h
            aud  = EXNESS:AUDUSD EVERY 1h
            nzd  = EXNESS:NZDUSD EVERY 1h
            antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h
            SYNCHRONIZE gold antipodean
        RULES
            WHEN zscore(gold.close / antipodean.close, 6) >= 2.0 AND POSITION.gold = 0
            THEN SELL gold
        """.trimIndent()

    private fun compile(): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private fun tick(
        symbol: String,
        ts: Long,
        price: String,
    ): Tick = Tick(symbol = symbol, price = BigDecimal(price), timestamp = ts, volume = BigDecimal.ONE)

    private fun bind(signals: MutableList<Signal>): CandleHub {
        val s = compile()
        val hub = CandleHub()
        s.declaredStreams.values.forEach { hub.register(it, retention = 20, strategyId = "test") }
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }
        return hub
    }

    /** Close all three constituents for the window that ends at `(i+1)*hour`. */
    private fun closeWindow(
        hub: CandleHub,
        i: Int,
        gold: String,
        aud: String,
        nzd: String,
    ) {
        hub.feed(tick(goldSym, i * hour, gold))
        hub.feed(tick(audSym, i * hour, aud))
        hub.feed(tick(nzdSym, i * hour, nzd))
    }

    @Test
    fun `the ratio z-score compiles and binds with gold primary`() {
        // Pure compile smoke: a gold/basket ratio z-score is a legal condition.
        assertThat(compile().declaredStreams).containsKey("antipodean")
    }

    @Test
    fun `no signal fires during warmup`() {
        val signals = mutableListOf<Signal>()
        val hub = bind(signals)
        // Fewer aligned windows than the z-score period (6) → never warm → no fire.
        for (i in 0..4) closeWindow(hub, i, "2000.0", "0.66", "0.60")
        assertThat(signals).isEmpty()
    }

    @Test
    fun `a plus two sigma ratio spike fires the entry`() {
        val signals = mutableListOf<Signal>()
        val hub = bind(signals)
        // A flat gold/basket ratio for the warmup window (basket index stays 100 since the
        // constituents are flat; gold is constant), so the z-score has a clean baseline.
        // A lone spike on a window of N-1 equal priors gives z = (N-1)/sqrt(N): for N=6 that
        // is ~2.04, just past the +2 entry, regardless of the spike's size.
        for (i in 0..8) closeWindow(hub, i, "2000.0", "0.66", "0.60")
        assertThat(signals).isEmpty()
        // Window 8 closes flat above (no spike yet at i=8's tick); spike at window 9.
        closeWindow(hub, 9, "2080.0", "0.66", "0.60") // closes window 8 (still 2000)
        closeWindow(hub, 10, "2080.0", "0.66", "0.60") // closes window 9 (the 2080 spike)
        assertThat(signals).isNotEmpty
    }
}
