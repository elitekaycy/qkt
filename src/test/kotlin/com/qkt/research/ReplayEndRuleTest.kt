package com.qkt.research

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayEndRuleTest {
    private val rule = ReplayEndRule { Money.of("100") }
    private val positions = StrategyPositionTracker().account

    private fun market() = OrderRequest.Market("m", "X", Side.BUY, Money.of("1"), TimeInForce.GTC, 0L)

    private fun limit(price: String) =
        OrderRequest.Limit("l", "X", Side.BUY, Money.of("1"), Money.of(price), TimeInForce.GTC, 0L)

    @Test
    fun `before the replay ends every order passes`() {
        assertThat(rule.evaluate(market(), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `after the end an order that would fill at the stale last price is refused`() {
        rule.ended = true

        assertThat(rule.evaluate(market(), positions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(limit("101"), positions)).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `after the end a resting order is accepted, since it simply never fills`() {
        rule.ended = true

        assertThat(rule.evaluate(limit("95"), positions)).isEqualTo(Decision.Approve)
    }
}
