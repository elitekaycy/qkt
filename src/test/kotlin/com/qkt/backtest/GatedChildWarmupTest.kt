package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.strategy.PerStreamWarmable
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A portfolio child wrapped for backtest gating must still advertise its warmup requirements,
 * or the per-stream warmup coordinator skips it and a PORTFOLIO backtest starts every child
 * cold — unlike the daemon, which seeds each child from venue history.
 */
class GatedChildWarmupTest {
    @Test
    fun `gated child exposes the inner strategy's per-stream warmup`() {
        val inner =
            AstCompiler().compile(
                (
                    Dsl.parse(
                        """
                        STRATEGY child VERSION 1
                        SYMBOLS
                          s = EXNESS:XAUUSD EVERY 4h WARMUP 80 BARS,
                          o = EXNESS:XAGUSD EVERY 4h WARMUP 80 BARS
                        RULES
                          WHEN percentile_rank(s.close / lag(o.close, 20), 40) <= 0.05 THEN FLATTEN
                        """.trimIndent(),
                    ) as ParseResult.Success
                ).value,
            ) as DslCompiledStrategy
        val gated = GatedChild("book:child", inner, hold = false, gateFor = { true }, flattenSymbols = emptyList())

        assertThat(gated).isInstanceOf(PerStreamWarmable::class.java)
        val specs = (gated as PerStreamWarmable).perStreamWarmup
        assertThat(specs).isEqualTo((inner as PerStreamWarmable).perStreamWarmup)
        assertThat(
            specs[WarmupStream("EXNESS:XAUUSD", TimeWindow.parse("4h"))],
        ).isEqualTo(WarmupSpec.Bars(TimeWindow.parse("4h"), 80))
    }
}
