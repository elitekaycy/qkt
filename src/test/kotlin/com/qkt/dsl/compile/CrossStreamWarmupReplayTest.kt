package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression for the live zero-trade incident: an expression-fed indicator over a
 * cross-stream expression (`percentile_rank(s.close / o.close, n)`) must be warm after
 * seeded-history replay, exactly as it is after a continuous backtest. If replay walks
 * stream `s` to completion before touching stream `o`, every `s` replay bar sees `o`
 * as Undefined, the indicator receives no input, and the rule stays Undefined for the
 * first `n` live bars.
 */
class CrossStreamWarmupReplayTest {
    private fun compile(src: String) =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    private fun candle(
        symbol: String,
        startMs: Long,
        close: String,
    ): Candle =
        Candle(
            symbol = symbol,
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = startMs,
            endTime = startMs + 60_000L,
        )

    @Test
    fun `cross-stream expression-fed indicator is warm after seeded replay`() {
        val s =
            compile(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  s = EXNESS:XAUUSD EVERY 1m WARMUP 10 BARS,
                  o = EXNESS:XAGUSD EVERY 1m WARMUP 10 BARS
                RULES
                  WHEN percentile_rank((s.close / lag(s.close, 2)) / (o.close / lag(o.close, 2)), 5) >= 0 THEN FLATTEN
                """.trimIndent(),
            )
        val hub = CandleHub()
        val sKey = s.declaredStreams.getValue("s")
        val oKey = s.declaredStreams.getValue("o")
        hub.register(sKey, retention = 20, strategyId = "test")
        hub.register(oKey, retention = 20, strategyId = "test")
        hub.seed(sKey, (0..9).map { candle("EXNESS:XAUUSD", it * 60_000L, "${100 + it}") })
        hub.seed(oKey, (0..9).map { candle("EXNESS:XAGUSD", it * 60_000L, "${50 + it}") })
        val received = mutableListOf<Signal>()
        s.bindToHub(hub, testStrategyContext()) { received += it }

        // percentile_rank(5) had ten seeded bars of input available, so the (always-true)
        // condition must fire on the first live bar — not after five more.
        var firedAfterBars = -1
        for (i in 10..20) {
            hub.feed(Tick("EXNESS:XAGUSD", BigDecimal("60"), i * 60_000L))
            hub.feed(Tick("EXNESS:XAUUSD", BigDecimal("110"), i * 60_000L))
            if (received.isNotEmpty() && firedAfterBars < 0) firedAfterBars = i - 10
        }
        assertThat(firedAfterBars).isEqualTo(1)
    }
}
