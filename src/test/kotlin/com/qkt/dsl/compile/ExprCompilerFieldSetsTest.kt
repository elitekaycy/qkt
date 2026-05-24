package com.qkt.dsl.compile

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerFieldSetsTest {
    @Test
    fun `CANDLE_FIELDS and META_FIELDS are disjoint`() {
        val overlap = ExprCompiler.CANDLE_FIELDS.intersect(ExprCompiler.META_FIELDS)
        assertThat(overlap)
            .`as`("a name in both sets would make <stream>.field resolution ambiguous")
            .isEmpty()
    }
}
