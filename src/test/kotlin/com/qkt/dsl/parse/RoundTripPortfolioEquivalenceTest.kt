package com.qkt.dsl.parse

import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RoundTripPortfolioEquivalenceTest {
    @Test
    fun `simple PORTFOLIO round trips`() {
        val text =
            """
            PORTFOLIO p VERSION 1
            IMPORT 'a.qkt' AS a
            RULES
                RUN a
            """.trimIndent()
        val parsed =
            (Parser(Lexer(text).tokenize()).parseFile() as ParseResult.Success).value
        val handwritten =
            ParsedFile.PortfolioFile(
                portfolio("p", version = 1) {
                    import("a.qkt", alias = "a")
                    rules { run("a") }
                },
            )
        assertThat(parsed).isEqualTo(handwritten)
    }

    @Test
    fun `PORTFOLIO with WHEN-RUN and HOLD round trips`() {
        val text =
            """
            PORTFOLIO p2 VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1h
            IMPORT 'a.qkt' AS a
            IMPORT 'b.qkt' AS b HOLD
            RULES
                WHEN btc.close > 100 RUN a
                RUN b
            """.trimIndent()
        val parsed =
            (Parser(Lexer(text).tokenize()).parseFile() as ParseResult.Success).value
        val handwritten =
            ParsedFile.PortfolioFile(
                portfolio("p2", version = 1) {
                    val btc = stream("btc", "BACKTEST", "BTCUSDT", "1h")
                    import("a.qkt", alias = "a")
                    import("b.qkt", alias = "b", hold = true)
                    rules {
                        whenRun(btc.close gt 100.bd, child = "a")
                        run("b")
                    }
                },
            )
        assertThat(parsed).isEqualTo(handwritten)
    }
}
