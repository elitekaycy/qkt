package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleAstTest {
    @Test
    fun `WhenThen pairs a condition with an action`() {
        val rule =
            WhenThen(
                cond = BoolLit(true),
                action = Buy(stream = "btc", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))),
            )
        assertThat(rule.cond).isEqualTo(BoolLit(true))
        assertThat(rule.action).isInstanceOf(Buy::class.java)
    }

    @Test
    fun `Buy carries stream alias and options`() {
        val buy = Buy(stream = "gold", opts = ActionOpts(sizing = SizeQty(NumLit(BigDecimal("2")))))
        assertThat(buy.stream).isEqualTo("gold")
        assertThat(buy.opts.sizing).isEqualTo(SizeQty(NumLit(BigDecimal("2"))))
    }

    @Test
    fun `ActionOpts has all-null defaults`() {
        val opts = ActionOpts()
        assertThat(opts.sizing).isNull()
        assertThat(opts.orderType).isNull()
        assertThat(opts.tif).isNull()
        assertThat(opts.bracket).isNull()
        assertThat(opts.oco).isNull()
    }

    @Test
    fun `CloseAll and CancelAll are singletons`() {
        assertThat(CloseAll).isSameAs(CloseAll)
        assertThat(CancelAll).isSameAs(CancelAll)
    }

    @Test
    fun `Market is the default order-type singleton`() {
        assertThat(Market).isSameAs(Market)
    }
}
