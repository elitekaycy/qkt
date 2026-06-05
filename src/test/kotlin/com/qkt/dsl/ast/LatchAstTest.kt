package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchAstTest {
    @Test
    fun `latch is an action with sensor, arm window, and entries`() {
        val latch =
            Latch(
                stream = "gold",
                sensor = BreakOffset(reference = null, offset = NumLit(java.math.BigDecimal("0.50"))),
                armWindow = DurationAst(300_000L),
                name = null,
                entries =
                    listOf(
                        LatchEntry(
                            order = LatchLimit(DirRel(DirSense.AGAINST, NumLit(java.math.BigDecimal("4")))),
                            bracket =
                                LatchBracket(
                                    stopLoss = DirRel(DirSense.AGAINST, NumLit(java.math.BigDecimal("12"))),
                                    takeProfit = DirRel(DirSense.WITH, NumLit(java.math.BigDecimal("5"))),
                                ),
                            sizing = SizeRiskAbs(NumLit(java.math.BigDecimal("250"))),
                            expire = DurationAst(7_200_000L),
                        ),
                    ),
            )
        assertThat(latch).isInstanceOf(ActionAst::class.java)
        assertThat(latch.sensor).isInstanceOf(BreakOffset::class.java)
        assertThat(latch.entries).hasSize(1)
        assertThat((latch.entries[0].order as LatchLimit).price.sense).isEqualTo(DirSense.AGAINST)
    }
}
