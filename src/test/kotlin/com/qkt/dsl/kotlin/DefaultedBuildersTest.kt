package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.StackLayer
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Builders called with only their required arguments leave every optional clause unset. */
class DefaultedBuildersTest {
    @Test
    fun `a bare sell carries no sizing, order type or attached orders`() {
        assertThat(ActionScope.sell(StreamRef("btc"))).isEqualTo(Sell("btc", ActionOpts()))
    }

    @Test
    fun `a layer sized by risk is a market layer at the entry`() {
        val risk = SizeRiskFrac(NumLit(BigDecimal("0.01")))

        assertThat(layer(risk)).isEqualTo(StackLayer(risk, null, null))
    }
}
