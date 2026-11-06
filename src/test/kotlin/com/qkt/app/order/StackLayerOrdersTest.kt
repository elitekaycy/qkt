package com.qkt.app.order

import com.qkt.broker.PositionAccountingMode
import com.qkt.common.FixedClock
import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizingAst
import com.qkt.execution.Immediate
import com.qkt.execution.LayerSpec
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StackLayerOrdersTest {
    private val orders = StackLayerOrders(FixedClock(0L)) { PositionAccountingMode.NETTING }

    private fun layer(
        sizing: SizingAst,
        resolved: BigDecimal? = null,
    ) = LayerSpec(index = 1, sizing = sizing, orderType = Market, trigger = Immediate, resolvedQuantity = resolved)

    @Test
    fun `a layer quantity comes from the compiler or a literal quantity`() {
        assertThat(orders.resolveLayerQuantity(layer(SizeNotional(NumLit(BigDecimal("500"))), BigDecimal("0.3"))))
            .isEqualByComparingTo("0.3")
        assertThat(orders.resolveLayerQuantity(layer(SizeQty(NumLit(BigDecimal("0.2")))))).isEqualByComparingTo("0.2")
    }

    @Test
    fun `an unresolved quantity that is not a literal is refused`() {
        assertThatThrownBy { orders.resolveLayerQuantity(layer(SizeQty(Ref("lots")))) }
            .hasMessageContaining("STACK layer qty must be a literal")
        assertThatThrownBy { orders.resolveLayerQuantity(layer(SizeNotional(NumLit(BigDecimal("500"))))) }
            .hasMessageContaining("requires resolution by ActionCompiler")
    }

    @Test
    fun `only an at trigger has a price`() {
        assertThatThrownBy { orders.resolveTriggerPrice(Immediate, BigDecimal("100")) }
            .hasMessageContaining("non-Immediate triggers must be At")
    }
}
