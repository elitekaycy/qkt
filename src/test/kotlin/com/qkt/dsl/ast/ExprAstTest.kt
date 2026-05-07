package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprAstTest {
    @Test
    fun `BinOp enumerates arithmetic and boolean operators`() {
        assertThat(BinOp.entries).containsExactlyInAnyOrder(
            BinOp.ADD,
            BinOp.SUB,
            BinOp.MUL,
            BinOp.DIV,
            BinOp.AND,
            BinOp.OR,
        )
    }

    @Test
    fun `Cmp enumerates the six comparisons`() {
        assertThat(Cmp.entries).containsExactlyInAnyOrder(
            Cmp.GT,
            Cmp.LT,
            Cmp.GE,
            Cmp.LE,
            Cmp.EQ,
            Cmp.NE,
        )
    }

    @Test
    fun `UnOp covers negation and logical not`() {
        assertThat(UnOp.entries).containsExactlyInAnyOrder(UnOp.NEG, UnOp.NOT)
    }

    @Test
    fun `NumLit holds a BigDecimal value`() {
        val lit = NumLit(java.math.BigDecimal("1.5"))
        assertThat(lit.value).isEqualByComparingTo("1.5")
    }

    @Test
    fun `BoolLit captures booleans`() {
        assertThat(BoolLit(true).value).isTrue()
        assertThat(BoolLit(false).value).isFalse()
    }

    @Test
    fun `Ref defaults to realtime when no snapshot is given`() {
        val r = Ref(name = "fast", snapshot = null)
        assertThat(r.snapshot).isNull()
    }

    @Test
    fun `StreamFieldRef binds a stream alias and a field name`() {
        val r = StreamFieldRef(stream = "btc", field = "close")
        assertThat(r.stream).isEqualTo("btc")
        assertThat(r.field).isEqualTo("close")
    }
}
