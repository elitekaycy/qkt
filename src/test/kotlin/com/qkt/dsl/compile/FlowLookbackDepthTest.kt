package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Trade-flow fields are read a whole number of bars back, at least one. */
class FlowLookbackDepthTest {
    private fun lookback(n: String) =
        IndicatorCall("LAG", listOf(StreamFieldRef("b", "buy_volume"), NumLit(BigDecimal(n))))

    @Test
    fun `the closing bar and fractional depths are refused`() {
        for (n in listOf("0", "1.5")) {
            assertThatThrownBy { ExprCompiler().compile(lookback(n)) }
                .describedAs(n)
                .hasMessageContaining("b.buy_volume is read a whole number of bars back, at least [1]")
        }
    }
}
