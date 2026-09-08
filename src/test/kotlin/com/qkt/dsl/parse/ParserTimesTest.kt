package com.qkt.dsl.parse

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserTimesTest {
    private fun parse(actionDsl: String): ParseResult<com.qkt.dsl.ast.StrategyAst> {
        val src =
            """
            STRATEGY x VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
                eur = BACKTEST:EURUSD EVERY 1m
            RULES
                WHEN gold.close > 0 THEN $actionDsl
            """.trimIndent()
        return Parser(Lexer(src).tokenize()).parseStrategy()
    }

    private fun action(actionDsl: String) =
        ((parse(actionDsl) as ParseResult.Success).value.rules[0] as WhenThen).action

    @Test
    fun `TIMES with a literal count parses onto the action`() {
        val buy = action("BUY gold SIZING 0.01 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 } TIMES 30") as Buy
        assertThat((buy.opts.times as NumLit).value).isEqualByComparingTo("30")
        assertThat(buy.opts.bracket).isNotNull
    }

    @Test
    fun `TIMES accepts any expression including CASE and account arithmetic`() {
        val strong = action("BUY gold SIZING 0.01 TIMES CASE WHEN gold.close > 100 THEN 5 ELSE 1 END") as Buy
        assertThat(strong.opts.times).isInstanceOf(CaseWhen::class.java)
        val balance = action("BUY gold SIZING 0.01 TIMES floor(ACCOUNT.balance / 25000)") as Buy
        assertThat(balance.opts.times).isNotNull
        val arithmetic = action("SELL eur SIZING 0.01 TIMES 2 * 5") as Sell
        assertThat(arithmetic.opts.times).isInstanceOf(BinaryOp::class.java)
    }

    @Test
    fun `TIMES may sit before or after the other clauses`() {
        val first = action("BUY gold SIZING 0.01 TIMES 3 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 }") as Buy
        val last = action("BUY gold SIZING 0.01 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 } TIMES 3") as Buy
        assertThat(first.opts).isEqualTo(last.opts)
    }

    @Test
    fun `TIMES composes with a multi-action rule across symbols`() {
        val block = action("BUY gold SIZING 0.01 TIMES 10 ; SELL eur SIZING 0.01 TIMES 10") as Block
        assertThat(block.actions).hasSize(2)
        assertThat((block.actions[0] as Buy).opts.times).isNotNull
        assertThat((block.actions[1] as Sell).opts.times).isNotNull
    }

    @Test
    fun `a duplicate TIMES clause is a parse error`() {
        val result = parse("BUY gold SIZING 0.01 TIMES 3 TIMES 4")
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        assertThat((result as ParseResult.Failure).errors.first().message).contains("duplicate TIMES")
    }

    @Test
    fun `TIMES is a keyword the language server can offer`() {
        assertThat(Lexer.keywordSpellings()).contains("TIMES")
    }
}
