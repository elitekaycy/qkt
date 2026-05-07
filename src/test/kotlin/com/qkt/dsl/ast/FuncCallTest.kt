package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FuncCallTest {
    @Test
    fun `FuncCall captures name and args`() {
        val f = FuncCall("ABS", listOf(NumLit(BigDecimal("-1"))))
        assertThat(f.name).isEqualTo("ABS")
        assertThat(f.args).hasSize(1)
    }
}
