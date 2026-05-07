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
}
