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
    fun `sync-grouped rule fires once per window after every member closes`() {
        val s =
            compile(
                """
                STRATEGY pairs VERSION 1
                SYMBOLS
                  gold   = EXNESS:XAUUSD EVERY 1m,
                  silver = EXNESS:XAGUSD EVERY 1m
                  SYNCHRONIZE gold silver
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

        // Drive gold only over three windows. Sync never completes → zero fires.
        for (t in 0L..180_000L step 60_000L) hub.feed(tick("EXNESS:XAUUSD", t, "1000"))
        assertThat(signals).isEmpty()

        // Drive silver across the same windows. Each completes → one fire each.
        for (t in 0L..180_000L step 60_000L) hub.feed(tick("EXNESS:XAGUSD", t, "25"))
        assertThat(signals).hasSize(3)
    }

    @Test
    fun `non-sync strategy fires per close`() {
        val s =
            compile(
                """
                STRATEGY solo VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 0 THEN BUY gold SIZING 0.1
                """.trimIndent(),
            )
        val hub = CandleHub()
        val key = s.declaredStreams.values.single()
        hub.register(key, retention = 10, strategyId = "test")
        val signals = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { sig -> signals.add(sig) }

        for (t in 0L..180_000L step 60_000L) hub.feed(tick("EXNESS:XAUUSD", t, "1000"))
        // Three closes at endTime 60_000, 120_000, 180_000.
        assertThat(signals).hasSize(3)
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

        // Drive btc alone — standalone rule fires per close.
        for (t in 0L..120_000L step 60_000L) hub.feed(tick("BYBIT_SPOT:BTCUSDT", t, "50000"))
        // 2 closes for btc, 0 for gold sync (no gold/silver ticks).
        assertThat(signals).hasSize(2)

        // Now drive gold+silver synchronously across two windows.
        for (t in 0L..120_000L step 60_000L) {
            hub.feed(tick("EXNESS:XAUUSD", t, "1000"))
            hub.feed(tick("EXNESS:XAGUSD", t, "25"))
        }
        // 2 more signals from the sync group, on top of the 2 btc signals.
        assertThat(signals).hasSize(4)
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
