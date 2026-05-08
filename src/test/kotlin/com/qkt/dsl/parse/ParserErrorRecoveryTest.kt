package com.qkt.dsl.parse

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserErrorRecoveryTest {
    @Test
    fun `surfaces multiple errors per file`() {
        val src = Files.readString(Path.of("src/test/resources/dsl/syntax_errors.qkt"))
        val r = Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Failure
        assertThat(r.errors.size).isGreaterThanOrEqualTo(3)
        // distinct line numbers prove sync recovered, not just a cascade
        assertThat(
            r.errors
                .map { it.line }
                .distinct()
                .size,
        ).isGreaterThanOrEqualTo(3)
    }
}
