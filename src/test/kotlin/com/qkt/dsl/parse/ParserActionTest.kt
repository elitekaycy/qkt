package com.qkt.dsl.parse

import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.CancelAll
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.CloseAll
import com.qkt.dsl.ast.Gtc
import com.qkt.dsl.ast.Limit
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.SizeQty
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserActionTest {
    @Test
    fun `BUY stream alias`() {
        val r = Parser(Lexer("BUY btc").tokenize()).parseAction() as Buy
        assertThat(r.stream).isEqualTo("btc")
    }

    @Test
    fun `BUY with sizing and order type`() {
        val r = Parser(Lexer("BUY btc SIZING 1 ORDER_TYPE = LIMIT AT 100 TIF GTC").tokenize()).parseAction() as Buy
        assertThat(r.opts.sizing).isInstanceOf(SizeQty::class.java)
        assertThat(r.opts.orderType).isInstanceOf(Limit::class.java)
        assertThat(r.opts.tif).isEqualTo(Gtc)
    }

    @Test
    fun `BUY with bracket`() {
        val r = Parser(Lexer("BUY btc BRACKET { STOP LOSS BY 0.5, TAKE PROFIT RR 3 }").tokenize()).parseAction() as Buy
        assertThat(r.opts.bracket).isInstanceOf(BracketAst::class.java)
    }

    @Test
    fun `CLOSE stream`() {
        val r = Parser(Lexer("CLOSE btc").tokenize()).parseAction() as Close
        assertThat(r.stream).isEqualTo("btc")
    }

    @Test
    fun `CLOSE_ALL`() {
        assertThat(Parser(Lexer("CLOSE_ALL").tokenize()).parseAction()).isEqualTo(CloseAll)
    }

    @Test
    fun `CANCEL stream`() {
        val r = Parser(Lexer("CANCEL btc").tokenize()).parseAction() as Cancel
        assertThat(r.stream).isEqualTo("btc")
    }

    @Test
    fun `CANCEL_ALL`() {
        assertThat(Parser(Lexer("CANCEL_ALL").tokenize()).parseAction()).isEqualTo(CancelAll)
    }

    @Test
    fun `LOG string`() {
        val r = Parser(Lexer("LOG 'hello'").tokenize()).parseAction() as Log
        assertThat(r.messageFormat).isEqualTo("hello")
        assertThat(r.level).isEqualTo(com.qkt.dsl.ast.LogLevel.INFO)
        assertThat(r.fields).isEmpty()
    }
}
