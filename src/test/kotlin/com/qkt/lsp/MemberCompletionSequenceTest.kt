package com.qkt.lsp

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Member completion past a SEQUENCE stage, and the symbols a portfolio file contributes. */
class MemberCompletionSequenceTest {
    private val symbols =
        DocumentSymbols.of(
            (
                Dsl.parseAny(
                    "STRATEGY s VERSION 1\nSYMBOLS\n    g = BACKTEST:XAUUSD EVERY 1m\n" +
                        "SEQUENCE setup ON g {\n    STAGE swept: g.low < 1\n    STAGE go: g.close > 1\n}\n",
                ) as ParseResult.Success
            ).value,
        )

    private fun labels(vararg chain: String) = MemberCompletion.items(chain.toList(), symbols).map { it.label }

    @Test
    fun `a known stage offers the stage members and an unknown one offers nothing`() {
        assertThat(labels("SEQUENCE", "setup", "swept")).isNotEmpty
        assertThat(labels("SEQUENCE", "setup", "missing")).isEmpty()
        assertThat(labels("SEQUENCE", "setup", "swept", "price")).isEmpty()
    }

    @Test
    fun `a stream alias offers its fields without extra aliases`() {
        assertThat(MemberCompletion.aliasItems("g", symbols).map { it.label }).contains("close")
    }

    @Test
    fun `a portfolio contributes its streams and import aliases`() {
        val parsed =
            Dsl.parseAny(
                "PORTFOLIO book VERSION 1\nSYMBOLS\n    btc = BACKTEST:BTCUSDT EVERY 1h\n" +
                    "IMPORT 'trend.qkt' AS trend\nRULES\n    RUN trend\n",
            ) as ParseResult.Success<ParsedFile>

        val portfolio = DocumentSymbols.of(parsed.value)

        assertThat(portfolio.streamAliases).containsExactly("btc")
        assertThat(portfolio.imports).containsExactly("trend")
    }
}
