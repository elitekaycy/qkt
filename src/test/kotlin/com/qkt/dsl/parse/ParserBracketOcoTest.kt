package com.qkt.dsl.parse

import com.qkt.dsl.ast.ChildAt
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildPct
import com.qkt.dsl.ast.ChildRr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserBracketOcoTest {
    @Test
    fun `bracket with stop loss BY and take profit RR`() {
        val r = Parser(Lexer("{ STOP LOSS BY 0.5, TAKE PROFIT RR 3 }").tokenize()).parseBracket()
        assertThat(r.stopLoss).isInstanceOf(ChildBy::class.java)
        assertThat(r.takeProfit).isInstanceOf(ChildRr::class.java)
    }

    @Test
    fun `bracket with stop loss AT and take profit AT`() {
        val r = Parser(Lexer("{ STOP LOSS AT 99, TAKE PROFIT AT 110 }").tokenize()).parseBracket()
        assertThat(r.stopLoss).isInstanceOf(ChildAt::class.java)
        assertThat(r.takeProfit).isInstanceOf(ChildAt::class.java)
    }

    @Test
    fun `bracket with stop only`() {
        val r = Parser(Lexer("{ STOP LOSS PCT 0.01 }").tokenize()).parseBracket()
        assertThat(r.stopLoss).isInstanceOf(ChildPct::class.java)
        assertThat(r.takeProfit).isNull()
    }

    @Test
    fun `bracket accepts single-token STOP_LOSS and TAKE_PROFIT spellings`() {
        // `STOP_LOSS` / `TAKE_PROFIT` (one token, as DEFAULTS uses) parse the same as the two-word form.
        val r = Parser(Lexer("{ STOP_LOSS BY 0.5, TAKE_PROFIT RR 3 }").tokenize()).parseBracket()
        assertThat(r.stopLoss).isInstanceOf(ChildBy::class.java)
        assertThat(r.takeProfit).isInstanceOf(ChildRr::class.java)
    }

    @Test
    fun `bracket accepts the two spellings interchangeably`() {
        val r = Parser(Lexer("{ STOP_LOSS AT 99, TAKE PROFIT AT 110 }").tokenize()).parseBracket()
        assertThat(r.stopLoss).isInstanceOf(ChildAt::class.java)
        assertThat(r.takeProfit).isInstanceOf(ChildAt::class.java)
    }

    @Test
    fun `oco with stop and limit`() {
        val r = Parser(Lexer("{ STOP AT 99, LIMIT AT 110 }").tokenize()).parseOco()
        assertThat(r.stop).isInstanceOf(ChildAt::class.java)
        assertThat(r.limit).isInstanceOf(ChildAt::class.java)
    }
}
