package com.qkt.app.order

import com.qkt.common.Side
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ChildArmedTrail
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StackEntryRef
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChildPricesTest {
    private fun n(value: String) = NumLit(BigDecimal(value))

    private val fill = BigDecimal("100")

    @Test
    fun `entry relative arithmetic is evaluated at the anchor`() {
        val expr = BinaryOp(BinOp.DIV, BinaryOp(BinOp.MUL, StackEntryRef, n("3")), n("2"))

        assertThat(evaluateAt(expr, fill)).isEqualByComparingTo("150")
        assertThat(referencesStackEntryRef(expr)).isTrue()
        assertThat(referencesStackEntryRef(n("1"))).isFalse()
    }

    @Test
    fun `logical operators and other expressions are not stack trigger arithmetic`() {
        assertThatThrownBy { evaluateAt(BinaryOp(BinOp.AND, n("1"), n("1")), fill) }
            .hasMessageContaining("unsupported op in stack trigger: AND")
        assertThatThrownBy { evaluateAt(Ref("atr"), fill) }
            .hasMessageContaining("unsupported trigger expression")
    }

    @Test
    fun `an rr target is the stop distance times the multiplier on the profit side`() {
        val stop = BigDecimal("2")
        val buy = computeChildPrice(ChildRr(n("3")), Side.BUY, fill, isStopLoss = false, tick = null, slDistance = stop)
        val sell = computeChildPrice(ChildRr(n("3")), Side.SELL, fill, false, tick = null, slDistance = stop)

        assertThat(buy).isEqualByComparingTo("106")
        assertThat(sell).isEqualByComparingTo("94")
    }

    @Test
    fun `an rr target needs a stop distance and is never a stop`() {
        assertThatThrownBy { computeChildPrice(ChildRr(n("3")), Side.BUY, fill, isStopLoss = false, tick = null) }
            .hasMessageContaining("requires a resolvable STOP LOSS distance")
        assertThatThrownBy { computeChildPrice(ChildRr(n("3")), Side.BUY, fill, isStopLoss = true, tick = null) }
            .hasMessageContaining("RR is only valid for TAKE PROFIT")
    }

    @Test
    fun `an armed trail stop starts one trail distance against the entry`() {
        val trail = ChildArmedTrail(n("5"), n("8"))

        assertThat(computeChildPrice(trail, Side.BUY, fill, isStopLoss = true, tick = null)).isEqualByComparingTo("95")
        val sellStop = computeChildPrice(trail, Side.SELL, fill, isStopLoss = true, tick = null)
        assertThat(sellStop).isEqualByComparingTo("105")
        assertThatThrownBy { computeChildPrice(trail, Side.BUY, fill, isStopLoss = false, tick = null) }
            .hasMessageContaining("only valid for STOP LOSS")
    }
}
