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

    @Test
    fun `a punctuation segment in a CHAIN symbol names the segment it found`() {
        val source = "STRATEGY s VERSION 1\nSYMBOLS\n    c = CHAIN:DERIBIT.(x) EVERY 1d\n"
        val message =
            runCatching { Dsl.parse(source) }
                .fold(
                    onSuccess = { (it as ParseResult.Failure).errors.joinToString { e -> e.message } },
                    onFailure = { it.message.orEmpty() },
                )
        assertThat(message)
            .contains(
                "expected a letters, digits or '_' name segment in a dotted HUB, CHAIN or OPTIONS symbol, got '('",
            )
    }
}
