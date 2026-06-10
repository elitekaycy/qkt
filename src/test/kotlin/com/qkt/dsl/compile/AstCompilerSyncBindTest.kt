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
 * #45 — Phase 35 Task 7. End-to-end integration of `AstCompiler.bindToHub` with
 * `CandleHub` sync groups. Verifies:
 *  - A sync-grouped rule does NOT fire on individual member close; waits for the
 *    full window to assemble.
 *  - A strategy without `SYNCHRONIZE` is unaffected (regression).
 *  - Three-member groups also gate correctly.
 *  - The non-sync path stays alive when a strategy has BOTH grouped and
 *    standalone streams.
 */
class AstCompilerSyncBindTest {
    private fun compile(src: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private fun tick(
        symbol: String,
        ts: Long,
        price: String = "100",
    ): Tick =
        Tick(
            symbol = symbol,
            price = BigDecimal(price),
            timestamp = ts,
            volume = BigDecimal.ONE,
        )

    @Test
    fun `sync-grouped rule evaluates once per window after every member closes`() {
        val s =
            compile(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                  gold   = EXNESS:XAUUSD EVERY 1m,
                  silver = EXNESS:XAGUSD EVERY 1m
                  SYNCHRONIZE gold silver
                RULES
                  WHEN gold.close > 500 THEN BUY gold SIZING 0.1
                """.trimIndent(),
            )
        val hub = CandleHub()
        s.declaredStreams.values.forEach { key ->
            hub.register(key, retention = 10, strategyId = "test")
        }
        val signals = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }

        // Gold alternates above/below 500 per window so each true window is a fresh
        // rising edge (actions are edge-gated). Drive gold only over three windows:
        // sync never completes → zero fires.
        val goldPrices = listOf("1000", "100", "1000", "100")
        for ((i, t) in (0L..180_000L step 60_000L).withIndex()) {
            hub.feed(tick("EXNESS:XAUUSD", t, goldPrices[i]))
        }
        assertThat(signals).isEmpty()

        // Drive silver across the same windows. Each window completes and evaluates:
        // w0 true (edge, fires), w1 false (re-arms), w2 true (edge, fires).
        for (t in 0L..180_000L step 60_000L) hub.feed(tick("EXNESS:XAGUSD", t, "25"))
        assertThat(signals).hasSize(2)
    }

    @Test
    fun `non-sync strategy evaluates per close`() {
        val s =
            compile(
                """
                STRATEGY solo VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 500 THEN BUY gold SIZING 0.1
                """.trimIndent(),
            )
        val hub = CandleHub()
        val key = s.declaredStreams.values.single()
        hub.register(key, retention = 10, strategyId = "test")
        val signals = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }

        // Three closes at endTime 60_000, 120_000, 180_000 with closes 1000, 100, 1000:
        // two rising edges → two fires (the middle close re-arms the rule).
        val prices = listOf("1000", "100", "1000", "100")
        for ((i, t) in (0L..180_000L step 60_000L).withIndex()) {
            hub.feed(tick("EXNESS:XAUUSD", t, prices[i]))
        }
        assertThat(signals).hasSize(2)
    }

    @Test
    fun `mixed grouped + standalone stream both fire on their own schedule`() {
        val s =
            compile(
                """
                STRATEGY mixed VERSION 1
                SYMBOLS
                  gold   = EXNESS:XAUUSD EVERY 1m,
                  silver = EXNESS:XAGUSD EVERY 1m,
                  btc    = BYBIT_SPOT:BTCUSDT EVERY 1m
                  SYNCHRONIZE gold silver
                RULES
                  WHEN gold.close > 0 THEN BUY gold SIZING 0.1
                  WHEN btc.close > 0 THEN BUY btc SIZING 0.1
                """.trimIndent(),
            )
        val hub = CandleHub()
        s.declaredStreams.values.forEach { key ->
            hub.register(key, retention = 10, strategyId = "test")
        }
        val signals = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }

        // Drive btc alone — the standalone rule fires on its first true close
        // (edge-gated thereafter); the sync rule stays silent with no gold/silver.
        for (t in 0L..120_000L step 60_000L) hub.feed(tick("BYBIT_SPOT:BTCUSDT", t, "50000"))
        assertThat(signals).hasSize(1)

        // Now drive gold+silver synchronously across two windows — the sync rule's
        // first completed window is its rising edge.
        for (t in 0L..120_000L step 60_000L) {
            hub.feed(tick("EXNESS:XAUUSD", t, "1000"))
            hub.feed(tick("EXNESS:XAGUSD", t, "25"))
        }
        assertThat(signals).hasSize(2)
    }

    @Test
    fun `three-member sync group gates until all three close`() {
        val s =
            compile(
                """
                STRATEGY triple VERSION 1
                SYMBOLS
                  gold     = EXNESS:XAUUSD EVERY 1m,
                  silver   = EXNESS:XAGUSD EVERY 1m,
                  platinum = EXNESS:XPTUSD EVERY 1m
                  SYNCHRONIZE gold silver platinum
                RULES
                  WHEN gold.close > 0 THEN BUY gold SIZING 0.1
                """.trimIndent(),
            )
        val hub = CandleHub()
        s.declaredStreams.values.forEach { key ->
            hub.register(key, retention = 10, strategyId = "test")
        }
        val signals = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }

        for (t in 0L..60_000L step 60_000L) hub.feed(tick("EXNESS:XAUUSD", t, "1000"))
        for (t in 0L..60_000L step 60_000L) hub.feed(tick("EXNESS:XAGUSD", t, "25"))
        assertThat(signals).isEmpty() // platinum missing
        for (t in 0L..60_000L step 60_000L) hub.feed(tick("EXNESS:XPTUSD", t, "900"))
        assertThat(signals).hasSize(1)
    }
}
