package com.qkt.dsl.parse

import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserLatchTest {
    private fun action(s: String) = Parser(Lexer(s).tokenize()).parseAction()

    @Test
    fun `parses a latch with one limit retrace entry`() {
        val latch =
            action(
                """
                LATCH gold OFFSET 0.50 ARM 5m {
                    ENTER LIMIT RETRACE 4 BRACKET { STOP LOSS AGAINST 12, TAKE PROFIT WITH 5 } SIZING RISK ${'$'} 250 EXPIRE 2h
                }
                """.trimIndent(),
            ) as Latch
        assertThat(latch.stream).isEqualTo("gold")
        assertThat(latch.armWindow.millis).isEqualTo(300_000L)
        val sensor = latch.sensor as BreakOffset
        assertThat(sensor.reference).isNull()
        assertThat(latch.entries).hasSize(1)
        val order = latch.entries[0].order as LatchLimit
        assertThat(order.price.sense).isEqualTo(DirSense.AGAINST)
        assertThat(latch.entries[0].expire?.millis).isEqualTo(7_200_000L)
    }

    @Test
    fun `parses market entry and multiple semicolon-separated entries`() {
        val latch =
            action(
                """
                LATCH gold OFFSET 0.50 ARM 5m {
                    ENTER MARKET BRACKET { STOP LOSS AGAINST 10, TAKE PROFIT WITH 30 } SIZING RISK ${'$'} 200 ;
                    ENTER LIMIT RETRACE 6 SIZING RISK ${'$'} 200
                }
                """.trimIndent(),
            ) as Latch
        assertThat(latch.entries).hasSize(2)
        assertThat(latch.entries[0].order).isEqualTo(LatchMarket)
    }

    @Test
    fun `FROM overrides the reference and AS names the latch`() {
        val latch =
            action(
                """
                LATCH gold OFFSET 0.50 FROM gold.high ARM 10m AS brk {
                    ENTER STOP WITH 5 SIZING RISK ${'$'} 100
                }
                """.trimIndent(),
            ) as Latch
        assertThat(latch.name).isEqualTo("brk")
        assertThat((latch.sensor as BreakOffset).reference).isNotNull
    }
}
