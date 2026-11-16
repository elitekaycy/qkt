package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A hub-driven sequence completion whose entry was suppressed stays available, so the entry is retried. */
class SequenceSuppressedHubTest {
    private val source =
        """
        STRATEGY s VERSION 1
        SYMBOLS
            gold = BACKTEST:GOLD EVERY 1m
        SEQUENCE setup ON gold {
            STAGE a: gold.close > 100
            STAGE b: gold.close > 200
        }
        RULES
            WHEN SEQUENCE.setup.complete THEN BUY gold SIZING 1
        """.trimIndent()

    @Test
    fun `a suppressed completion fires again on the next bar and an accepted one is consumed`() {
        val strategy = AstCompiler().compile((Dsl.parse(source) as ParseResult.Success).value) as DslCompiledStrategy
        val hub = CandleHub()
        hub.register(strategy.declaredStreams.values.single(), retention = 5, strategyId = "s")
        val ctx = testStrategyContext()
        val signals = mutableListOf<Signal>()
        strategy.bindToHub(hub, ctx) { signal ->
            signals += signal
            if (signals.size == 1) ctx.submissions.recordSuppressed() else ctx.submissions.recordAccepted()
        }

        // One tick per bar; each tick closes the previous bar.
        listOf("90", "150", "250", "260", "270", "280").forEachIndexed { i, price ->
            hub.feed(Tick("BACKTEST:GOLD", BigDecimal(price), i * 60_000L, volume = BigDecimal.ONE))
        }

        assertThat(signals.filterIsInstance<Signal.Buy>()).hasSize(2)
    }
}
