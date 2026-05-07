package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UnsupportedAstTest {
    private val ec = ExprCompiler()

    @Test
    fun `BETWEEN is not supported in 11b`() {
        assertThatThrownBy {
            ec.compile(Between(NumLit(BigDecimal.ONE), NumLit(BigDecimal.ZERO), NumLit(BigDecimal("2"))))
        }.hasMessageContaining("unsupported")
    }

    @Test
    fun `IN-list is not supported in 11b`() {
        assertThatThrownBy { ec.compile(InList(NumLit(BigDecimal.ONE), listOf(NumLit(BigDecimal.ONE)))) }
            .hasMessageContaining("unsupported")
    }

    @Test
    fun `CROSSES is not supported in 11b`() {
        assertThatThrownBy {
            ec.compile(Crosses(CrossDir.ABOVE, NumLit(BigDecimal.ONE), NumLit(BigDecimal.ZERO)))
        }.hasMessageContaining("unsupported")
    }

    @Test
    fun `CASE WHEN is not supported in 11b`() {
        assertThatThrownBy { ec.compile(CaseWhen(emptyList(), NumLit(BigDecimal.ZERO))) }
            .hasMessageContaining("unsupported")
    }

    @Test
    fun `aggregates are not supported in 11b`() {
        assertThatThrownBy {
            ec.compile(Aggregate(AggFn.MAX, StreamFieldRef("btc", "close"), SinceOpen))
        }.hasMessageContaining("unsupported")
    }

    @Test
    fun `account refs are not supported in 11b`() {
        assertThatThrownBy { ec.compile(AccountRef("equity")) }.hasMessageContaining("unsupported")
    }

    @Test
    fun `position refs are not supported in 11b`() {
        assertThatThrownBy { ec.compile(PositionRef("btc")) }.hasMessageContaining("unsupported")
    }
}
