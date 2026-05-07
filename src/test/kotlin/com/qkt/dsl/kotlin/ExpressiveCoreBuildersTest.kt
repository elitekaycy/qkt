package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpressiveCoreBuildersTest {
    @Test
    fun `between builds Between AST`() {
        val expr = 5.bd.between(1.bd, 10.bd)
        assertThat(expr).isInstanceOf(Between::class.java)
    }

    @Test
    fun `inList builds InList AST`() {
        val expr = 3.bd.inList(1.bd, 3.bd, 5.bd)
        assertThat(expr).isInstanceOf(InList::class.java)
    }

    @Test
    fun `crossesAbove builds Crosses ABOVE`() {
        val expr = 1.bd crossesAbove 0.bd
        assertThat((expr as Crosses).direction).isEqualTo(CrossDir.ABOVE)
    }

    @Test
    fun `crossesBelow builds Crosses BELOW`() {
        val expr = 1.bd crossesBelow 0.bd
        assertThat((expr as Crosses).direction).isEqualTo(CrossDir.BELOW)
    }

    @Test
    fun `caseWhen builds CaseWhen AST`() {
        val expr = caseWhen(1.bd.gt(0.bd) to 5.bd, elseExpr = 0.bd)
        assertThat(expr).isInstanceOf(CaseWhen::class.java)
    }

    @Test
    fun `Account refs`() {
        assertThat(Account.realizedPnl).isEqualTo(AccountRef("realized_pnl"))
        assertThat(Account.unrealizedPnl).isEqualTo(AccountRef("unrealized_pnl"))
        assertThat(Account.totalPnl).isEqualTo(AccountRef("total_pnl"))
    }

    @Test
    fun `position helpers`() {
        val btc = StreamRef("btc")
        assertThat(position(btc)).isEqualTo(PositionRef("btc"))
        assertThat(positionAvgPrice(btc)).isEqualTo(StateAccessor(StateSource.POSITION_AVG_PRICE, "btc"))
    }

    @Test
    fun `math helpers build FuncCall`() {
        assertThat(abs(1.bd)).isEqualTo(FuncCall("ABS", listOf(1.bd)))
        assertThat(min(1.bd, 2.bd)).isEqualTo(FuncCall("MIN", listOf(1.bd, 2.bd)))
        assertThat(max(1.bd, 2.bd, 3.bd)).isEqualTo(FuncCall("MAX", listOf(1.bd, 2.bd, 3.bd)))
    }
}
