package com.qkt.dsl.compile

import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UnsupportedAstTest {
    private val ec = ExprCompiler()

    @Test
    fun `OPEN_ORDERS state is not supported in 11c2`() {
        assertThatThrownBy {
            ec.compile(StateAccessor(StateSource.OPEN_ORDERS, "btc"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
