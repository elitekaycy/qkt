package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SnapshotOpen
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerLetTest {
    @Test
    fun `single LET binding is substituted at the use site`() {
        val lets = listOf(LetDecl("two", NumLit(BigDecimal("2"))))
        val expr = BinaryOp(BinOp.ADD, Ref("two"), NumLit(BigDecimal("3")))
        val resolved = LetResolver(lets).resolve(expr)
        assertThat(resolved)
            .isEqualTo(BinaryOp(BinOp.ADD, NumLit(BigDecimal("2")), NumLit(BigDecimal("3"))))
    }

    @Test
    fun `chained LETs resolve transitively`() {
        val lets =
            listOf(
                LetDecl("a", NumLit(BigDecimal("1"))),
                LetDecl("b", BinaryOp(BinOp.ADD, Ref("a"), NumLit(BigDecimal("1")))),
            )
        val resolved = LetResolver(lets).resolve(Ref("b"))
        assertThat(resolved)
            .isEqualTo(BinaryOp(BinOp.ADD, NumLit(BigDecimal("1")), NumLit(BigDecimal("1"))))
    }

    @Test
    fun `unknown reference is rejected`() {
        assertThatThrownBy { LetResolver(emptyList()).resolve(Ref("missing")) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `snapshot references are kept intact`() {
        val lets = listOf(LetDecl("x", NumLit(BigDecimal.ONE)))
        val expr = Ref("x", snapshot = SnapshotOpen)
        val resolved = LetResolver(lets).resolve(expr)
        assertThat(resolved).isEqualTo(Ref("x", snapshot = SnapshotOpen))
    }

    @Test
    fun `snapshot reference to unknown LET is rejected`() {
        assertThatThrownBy { LetResolver(emptyList()).resolve(Ref("missing", snapshot = SnapshotOpen)) }
            .isInstanceOf(IllegalStateException::class.java)
    }
}
