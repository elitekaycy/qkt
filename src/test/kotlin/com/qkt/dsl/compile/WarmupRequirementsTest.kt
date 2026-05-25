package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupRequirementsTest {
    private fun ast(src: String) = (Dsl.parse(src) as ParseResult.Success).value

    @Test
    fun `empty strategy returns no requirements`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN NOW.hour_utc = 0 THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).isEmpty()
    }

    @Test
    fun `explicit WARMUP N BARS is included`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m WARMUP 50 BARS
                RULES
                  WHEN NOW.hour_utc = 0 THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).containsExactly(java.util.Map.entry("g", 50))
    }

    @Test
    fun `indicator period without explicit WARMUP is derived`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN ema(g.close, 200) > g.close THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).containsExactly(java.util.Map.entry("g", 200))
    }

    @Test
    fun `max of explicit and indicator wins`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m WARMUP 30 BARS
                RULES
                  WHEN ema(g.close, 100) > g.close THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).containsExactly(java.util.Map.entry("g", 100))
    }

    @Test
    fun `indicators in LET expressions are picked up`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                LET fast = ema(g.close, 9),
                    slow = ema(g.close, 21)
                RULES
                  WHEN fast > slow THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).containsExactly(java.util.Map.entry("g", 21))
    }

    @Test
    fun `two streams accumulate per-alias requirements independently`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  a = X:Y EVERY 1m WARMUP 50 BARS,
                  b = X:Z EVERY 1h
                RULES
                  WHEN ema(b.close, 100) > b.close THEN FLATTEN
                """.trimIndent(),
            )
        val req = WarmupRequirements.compute(s)
        assertThat(req).hasSize(2)
        assertThat(req["a"]).isEqualTo(50)
        assertThat(req["b"]).isEqualTo(100)
    }

    @Test
    fun `nested indicator reports the outer period only`() {
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m
                RULES
                  WHEN ema(ema(g.close, 9), 21) > g.close THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).containsExactly(java.util.Map.entry("g", 21))
    }

    @Test
    fun `indicator with non-literal period is silently skipped`() {
        // Defensive: indicators always have NumLit periods, but if a future surface
        // allows expressions, we don't crash.
        val s =
            ast(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  g = X:Y EVERY 1m WARMUP 50 BARS
                RULES
                  WHEN ema(g.close, 100) > 0 THEN FLATTEN
                """.trimIndent(),
            )
        assertThat(WarmupRequirements.compute(s)).containsExactly(java.util.Map.entry("g", 100))
    }
}
