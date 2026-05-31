package com.qkt.execution

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestWithStrategyIdTest {
    @Test
    fun `withStrategyId stamps Market parent`() {
        val m = market("m1", strategyId = "")
        val stamped = m.withStrategyId("alpha") as OrderRequest.Market
        assertThat(stamped.strategyId).isEqualTo("alpha")
    }

    @Test
    fun `withStrategyId stamps StandaloneOCO parent and both legs`() {
        val leg1 = limit("l1", price = "1.10", strategyId = "")
        val leg2 = limit("l2", price = "1.20", strategyId = "")
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = leg1,
                leg2 = leg2,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = oco.withStrategyId("alpha") as OrderRequest.StandaloneOCO
        assertThat(stamped.strategyId).isEqualTo("alpha")
        assertThat(stamped.leg1.strategyId).isEqualTo("alpha")
        assertThat(stamped.leg2.strategyId).isEqualTo("alpha")
    }

    @Test
    fun `withStrategyId stamps OTO parent and all children`() {
        val parent = market("p1", strategyId = "")
        val child1 = limit("c1", price = "1.10", strategyId = "")
        val child2 = limit("c2", price = "1.20", strategyId = "")
        val oto =
            OrderRequest.OTO(
                id = "oto1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child1, child2),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = oto.withStrategyId("alpha") as OrderRequest.OTO
        assertThat(stamped.strategyId).isEqualTo("alpha")
        assertThat(stamped.parent.strategyId).isEqualTo("alpha")
        assertThat(stamped.children).allSatisfy { assertThat(it.strategyId).isEqualTo("alpha") }
    }

    @Test
    fun `withStrategyId stamps Bracket parent and entry`() {
        val entry = limit("e1", price = "4500", strategyId = "")
        val bracket =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = entry,
                takeProfit = Money.of("4600"),
                stopLoss = StopLossSpec.Fixed(Money.of("4400")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = bracket.withStrategyId("alpha") as OrderRequest.Bracket
        assertThat(stamped.strategyId).isEqualTo("alpha")
        assertThat(stamped.entry.strategyId).isEqualTo("alpha")
    }

    @Test
    fun `withStrategyId stamps ScaleOut parent and basis`() {
        val basis = market("ba1", strategyId = "")
        val scale =
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("3"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("100000"), Money.of("0.5"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = scale.withStrategyId("alpha") as OrderRequest.ScaleOut
        assertThat(stamped.strategyId).isEqualTo("alpha")
        assertThat(stamped.basis.strategyId).isEqualTo("alpha")
    }

    @Test
    fun `withStrategyId stamps TimeExit parent and target`() {
        val target = limit("t1", price = "80000", strategyId = "")
        val timeExit =
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("1"),
                target = target,
                deadline = java.time.Instant.parse("2030-01-01T00:00:00Z"),
                onExpiry = ExpiryAction.CANCEL,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = timeExit.withStrategyId("alpha") as OrderRequest.TimeExit
        assertThat(stamped.strategyId).isEqualTo("alpha")
        assertThat(stamped.target.strategyId).isEqualTo("alpha")
    }

    @Test
    fun `withStrategyId overwrites an existing strategyId on nested sub-request`() {
        val leg1 = limit("l1", price = "1.10", strategyId = "old")
        val leg2 = limit("l2", price = "1.20", strategyId = "old")
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = leg1,
                leg2 = leg2,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "old",
            )
        val stamped = oco.withStrategyId("new") as OrderRequest.StandaloneOCO
        assertThat(stamped.strategyId).isEqualTo("new")
        assertThat(stamped.leg1.strategyId).isEqualTo("new")
        assertThat(stamped.leg2.strategyId).isEqualTo("new")
    }

    private fun market(
        id: String,
        strategyId: String,
    ): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = "EURUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = strategyId,
        )

    private fun limit(
        id: String,
        price: String,
        strategyId: String,
    ): OrderRequest.Limit =
        OrderRequest.Limit(
            id = id,
            symbol = "EURUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            limitPrice = Money.of(price),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = strategyId,
        )
}
