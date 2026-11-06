package com.qkt.dsl.parse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A HUB symbol's dotted name is built from letter, digit and underscore segments only. */
class DottedSymbolSegmentTest {
    @Test
    fun `a punctuation segment in a HUB symbol is a located parse error`() {
        val result = Dsl.parse("STRATEGY s VERSION 1\nSYMBOLS\n    c = HUB:cal.(x) EVERY 1d\n") as ParseResult.Failure

        assertThat(result.errors.map { it.message })
            .contains("expected a letters, digits or '_' name segment in a HUB symbol, got '('")
    }
}
