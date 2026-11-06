package com.qkt.research

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayEndRuleShapesTest {
    private val positions = StrategyPositionTracker().account
    private val one = Money.of("1")
    private val market = OrderRequest.Market("m", "X", Side.BUY, one, TimeInForce.GTC, 0L)

    private fun ended(last: String?) = ReplayEndRule { last?.let { Money.of(it) } }.also { it.ended = true }

    private fun limit(
        side: Side,
        price: String,
    ) = OrderRequest.Limit("l", "X", side, one, Money.of(price), TimeInForce.GTC, 0L)

    @Test
    fun `a sell limit at or under the last price executes now and is refused`() {
        val rule = ended("100")

        assertThat(rule.evaluate(limit(Side.SELL, "99"), positions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(limit(Side.SELL, "101"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a limit on a symbol with no last price rests and is accepted`() {
        assertThat(ended(null).evaluate(limit(Side.BUY, "100"), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `brackets, OTOs and time exits are judged by the order that enters`() {
        val rule = ended("100")
        val stop = OrderRequest.Stop("s", "X", Side.SELL, one, Money.of("90"), TimeInForce.GTC, 0L)
        val bracket =
            OrderRequest.Bracket(
                "b",
                "X",
                Side.BUY,
                one,
                market,
                Money.of("110"),
                StopLossSpec.Fixed(Money.of("90")),
                TimeInForce.GTC,
                0L,
            )
        val oto = OrderRequest.OTO("o", "X", Side.BUY, one, market, listOf(stop), TimeInForce.GTC, 0L)
        val restingExit =
            OrderRequest.TimeExit(
                "t",
                "X",
                Side.BUY,
                one,
                limit(Side.BUY, "90"),
                Instant.EPOCH,
                ExpiryAction.CANCEL,
                TimeInForce.GTC,
                0L,
            )

        assertThat(rule.evaluate(bracket, positions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(oto, positions)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(restingExit, positions)).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(stop, positions)).isEqualTo(Decision.Approve)
    }
}
