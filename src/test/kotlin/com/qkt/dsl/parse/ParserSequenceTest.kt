package com.qkt.dsl.parse

import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserSequenceTest {
    private fun parse(source: String) = (Dsl.parse(source.trimIndent()) as ParseResult.Success).value

    @Test
    fun `parses top-level sequence declaration with stage timeouts`() {
        val ast =
            parse(
                """
                STRATEGY seq VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                SEQUENCE sweep_reclaim ON gold {
                    STAGE swept: gold.low < 99
                    STAGE reclaimed WITHIN 30m: gold.close > 100
                    STAGE go WITHIN 15m: gold.close > 101
                }
                RULES
                    WHEN SEQUENCE.sweep_reclaim.complete THEN BUY gold SIZING 1
                """,
            )

        assertThat(ast.sequences).hasSize(1)
        val sequence = ast.sequences.single()
        assertThat(sequence.name).isEqualTo("sweep_reclaim")
        assertThat(sequence.stream).isEqualTo("gold")
        assertThat(sequence.stages.map { it.name }).containsExactly("swept", "reclaimed", "go")
        assertThat(sequence.stages[1].within?.millis).isEqualTo(1_800_000L)
    }

    @Test
    fun `parses sequence accessors`() {
        val ast =
            parse(
                """
                STRATEGY seq VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                SEQUENCE sweep ON gold {
                    STAGE swept: gold.low < 99
                    STAGE reclaimed: gold.close > 100
                }
                RULES
                    WHEN SEQUENCE.sweep.stage = 1 THEN BUY gold SIZING 1
                    WHEN SEQUENCE.sweep.swept.price < 99 THEN SELL gold SIZING 1
                """,
            )

        val first = ast.rules[0] as WhenThen
        val second = ast.rules[1] as WhenThen
        assertThat(first.cond.toString()).contains("SequenceAccessor(sequence=sweep, stage=null, field=stage)")
        assertThat(second.cond.toString()).contains("SequenceAccessor(sequence=sweep, stage=swept, field=price)")
    }
}
