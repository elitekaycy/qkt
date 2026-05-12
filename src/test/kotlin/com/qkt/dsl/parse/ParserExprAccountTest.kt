package com.qkt.dsl.parse

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserExprAccountTest {
    private fun expr(s: String): ExprAst {
        val r = Parser(Lexer("STRATEGY s VERSION 1\nLET x = $s").tokenize()).parseStrategy() as ParseResult.Success
        return r.value.lets[0].expr
    }

    @Test
    fun `parses ACCOUNT field`() {
        val e = expr("ACCOUNT.equity") as AccountRef
        assertThat(e.field).isEqualTo("equity")
    }

    @Test
    fun `parses POSITION alias`() {
        val e = expr("POSITION.btc") as PositionRef
        assertThat(e.stream).isEqualTo("btc")
    }

    @Test
    fun `parses POSITION_AVG_PRICE alias`() {
        val e = expr("POSITION_AVG_PRICE.btc") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.POSITION_AVG_PRICE)
        assertThat(e.key).isEqualTo("btc")
    }

    @Test
    fun `parses OPEN_ORDERS alias`() {
        val e = expr("OPEN_ORDERS.btc") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.OPEN_ORDERS)
        assertThat(e.key).isEqualTo("btc")
    }

    @Test
    fun `parses bare SYMBOL placeholder`() {
        val e = expr("SYMBOL") as Ref
        assertThat(e.name).isEqualTo("__SYMBOL__")
    }

    @Test
    fun `parses POSITION dot quantity`() {
        val e = expr("POSITION.btc.quantity") as PositionRef
        assertThat(e.stream).isEqualTo("btc")
    }

    @Test
    fun `parses POSITION dot entry_price`() {
        val e = expr("POSITION.btc.entry_price") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.POSITION_AVG_PRICE)
        assertThat(e.key).isEqualTo("btc")
    }

    @Test
    fun `parses POSITION dot pnl`() {
        val e = expr("POSITION.btc.pnl") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.POSITION_PNL)
    }

    @Test
    fun `parses POSITION dot unrealized_pnl`() {
        val e = expr("POSITION.btc.unrealized_pnl") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.POSITION_UNREALIZED_PNL)
    }

    @Test
    fun `parses POSITION dot realized_pnl`() {
        val e = expr("POSITION.btc.realized_pnl") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.POSITION_REALIZED_PNL)
    }

    @Test
    fun `parses POSITION dot holding_duration`() {
        val e = expr("POSITION.btc.holding_duration") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.POSITION_HOLDING_DURATION)
    }

    @Test
    fun `parses POSITION dot mfe`() {
        val e = expr("POSITION.btc.mfe") as StateAccessor
        assertThat(e.source).isEqualTo(StateSource.POSITION_MFE)
        assertThat(e.key).isEqualTo("btc")
    }
}
