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
}
