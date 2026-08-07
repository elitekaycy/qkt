package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A rule listening on one stream whose action targets ANOTHER stream must order the
 * action's symbol — not whatever candle happened to trigger the evaluation. Routing
 * the evaluating candle's symbol sends real orders to the wrong instrument.
 */
class CrossStreamActionRoutingTest {
    @Test
    fun `cross-stream action orders the action's target symbol`() {
        val src =
            """
            STRATEGY crossroute VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1m,
              silver = EXNESS:XAGUSD EVERY 1m
            RULES
              WHEN gold.close > 100
              THEN BUY gold SIZING 1 ; BUY silver SIZING 1 ORDER_TYPE = LIMIT AT 20
            """.trimIndent()
        val strategy =
            AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 10, strategyId = "t") }
        val signals = mutableListOf<Signal>()
        strategy.bindToHub(hub, testStrategyContext()) { signals.add(it) }

        // The rule anchors on the block's primary action (gold), so the silver leg
        // evaluates with GOLD's candle as context — exactly the case where routing by
        // the evaluating candle's symbol sends silver's order to gold.
        hub.feed(Tick("EXNESS:XAGUSD", BigDecimal("25"), 0L))
        hub.feed(Tick("EXNESS:XAGUSD", BigDecimal("25"), 60_000L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 0L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 60_000L))

        val submits = signals.filterIsInstance<Signal.Submit>()
        assertThat(submits).isNotEmpty
        val req = submits.single().request as OrderRequest.Limit
        // The silver leg must order silver even though gold's candle fired the rule.
        assertThat(req.symbol).isEqualTo("EXNESS:XAGUSD")
        assertThat(req.limitPrice).isEqualByComparingTo("20")
    }

    @Test
    fun `cross-stream market order before the target's first closed bar is skipped, not fatal`() {
        // The rule anchors on silver (primary action), so the gold leg evaluates with silver's
        // candle as context and must price gold from the hub. Gold has no closed bar yet — a
        // transient warm-up state, common in portfolio replays where members' data starts at
        // different times. The order is skipped for this evaluation (like every other
        // undefined-during-warm-up price), never a fatal error that kills the whole backtest.
        val src =
            """
            STRATEGY nobar VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1m,
              silver = EXNESS:XAGUSD EVERY 1m
            RULES
              WHEN gold.close > 100
              THEN BUY gold SIZING 1 ; BUY silver SIZING 1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT PCT 2 }
            """.trimIndent()
        val strategy =
            AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

        val hub = CandleHub()
        strategy.declaredStreams.values.forEach { hub.register(it, retention = 10, strategyId = "t") }
        val signals = mutableListOf<Signal>()
        strategy.bindToHub(hub, testStrategyContext()) { signals.add(it) }

        // Only gold ticks: gold's first bar closes (150 > 100 fires) while silver has no closed
        // bar. The silver bracket leg needs silver's price for its stop/target and cannot get one —
        // a transient warm-up state (portfolio members' data can start at different times). The leg
        // is skipped like any other undefined-during-warm-up price; the gold leg still fires.
        // Crucially: no fatal "No closed bar yet" error killing the run.
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 0L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 60_000L))
        assertThat(signals.filterIsInstance<Signal.Buy>().map { it.symbol })
            .containsExactly("EXNESS:XAUUSD")
        assertThat(signals.filterIsInstance<Signal.Submit>()).isEmpty()

        // Rules are edge-triggered: a false gold bar (close 90) re-arms the condition, and by the
        // next true close silver's first bar exists — the re-fire prices the bracket leg normally.
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("90"), 70_000L))
        hub.feed(Tick("EXNESS:XAGUSD", BigDecimal("25"), 80_000L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 120_000L))
        hub.feed(Tick("EXNESS:XAGUSD", BigDecimal("25"), 120_000L))
        hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("150"), 180_000L))
        val brackets = signals.filterIsInstance<Signal.Submit>().map { it.request.symbol }
        assertThat(brackets).contains("EXNESS:XAGUSD")
    }
}
