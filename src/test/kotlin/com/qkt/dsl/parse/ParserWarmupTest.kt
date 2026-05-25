package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserWarmupTest {
    @Test
    fun `EVERY 5m WARMUP 50 BARS populates warmupBars`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
            """.trimIndent()
        val ast = (Dsl.parse(src) as ParseResult.Success).value
        val stream = ast.streams.single()
        assertThat(stream.warmupBars).isEqualTo(50)
    }

    @Test
    fun `absent WARMUP leaves warmupBars null`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
            """.trimIndent()
        val ast = (Dsl.parse(src) as ParseResult.Success).value
        assertThat(ast.streams.single().warmupBars).isNull()
    }

    @Test
    fun `WARMUP 0 BARS is rejected at parse time`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m WARMUP 0 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
            """.trimIndent()
        val errors = (Dsl.parse(src) as ParseResult.Failure).errors
        assertThat(errors).isNotEmpty
        assertThat(errors.first().message).contains("WARMUP")
    }

    @Test
    fun `WARMUP with non-integer literal is rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              g = EXNESS:XAUUSD EVERY 5m WARMUP 50.5 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
            """.trimIndent()
        val errors = (Dsl.parse(src) as ParseResult.Failure).errors
        assertThat(errors).isNotEmpty
    }

    @Test
    fun `per-stream WARMUP attaches independently in multi-stream`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              a = EXNESS:XAUUSD EVERY 5m WARMUP 30 BARS,
              b = EXNESS:XAGUSD EVERY 1h WARMUP 10 BARS
            RULES
              WHEN NOW.hour_utc = 0 THEN FLATTEN
            """.trimIndent()
        val ast = (Dsl.parse(src) as ParseResult.Success).value
        val streams = ast.streams.associateBy { it.alias }
        assertThat(streams["a"]!!.warmupBars).isEqualTo(30)
        assertThat(streams["b"]!!.warmupBars).isEqualTo(10)
    }
}
