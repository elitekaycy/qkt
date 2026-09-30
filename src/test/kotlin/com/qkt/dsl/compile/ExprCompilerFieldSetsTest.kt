package com.qkt.dsl.compile

import com.qkt.dsl.DslVocabulary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerFieldSetsTest {
    @Test
    fun `candle fields and meta fields are disjoint`() {
        val overlap = DslVocabulary.candleFields.intersect(DslVocabulary.metaFields.toSet())
        assertThat(overlap)
            .`as`("a name in both sets would make <stream>.field resolution ambiguous")
            .isEmpty()
    }
}
