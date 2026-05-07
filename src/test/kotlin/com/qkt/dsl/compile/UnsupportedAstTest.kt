package com.qkt.dsl.compile

import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.SinceOpen
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreamFieldRef
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UnsupportedAstTest {
    private val ec = ExprCompiler()

    @Test
    fun `aggregates are not supported in 11c1`() {
        assertThatThrownBy {
            ec.compile(Aggregate(AggFn.MAX, StreamFieldRef("btc", "close"), SinceOpen))
        }.hasMessageContaining("unsupported")
    }

    @Test
    fun `OPEN_ORDERS state is not supported in 11c1`() {
        assertThatThrownBy {
            ec.compile(StateAccessor(StateSource.OPEN_ORDERS, "btc"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
