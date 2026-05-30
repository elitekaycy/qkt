package com.qkt.execution

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestWithExpiresAtTest {
    private fun limit(id: String): OrderRequest.Limit =
        OrderRequest.Limit(
            id = id,
            symbol = "EURUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            limitPrice = Money.of("1.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    @Test
    fun `withExpiresAt stamps Limit`() {
        val stamped = limit("l1").withExpiresAt(1700000000000L) as OrderRequest.Limit
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
    }

    @Test
    fun `withExpiresAt propagates into Bracket entry`() {
        val br =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = limit("e1"),
                takeProfit = Money.of("4600"),
                stopLoss = StopLossSpec.Fixed(Money.of("4400")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = br.withExpiresAt(1700000000000L) as OrderRequest.Bracket
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.entry as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
    }

    @Test
    fun `withExpiresAt propagates into both StandaloneOCO legs`() {
        val oco =
            OrderRequest.StandaloneOCO(
                id = "o1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1"),
                leg2 = limit("l2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = oco.withExpiresAt(1700000000000L) as OrderRequest.StandaloneOCO
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.leg1 as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.leg2 as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
    }

    @Test
    fun `withExpiresAt propagates into OTO parent and all children`() {
        val oto =
            OrderRequest.OTO(
                id = "oto1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = limit("p1"),
                children = listOf(limit("c1"), limit("c2")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val stamped = oto.withExpiresAt(1700000000000L) as OrderRequest.OTO
        assertThat(stamped.expiresAt).isEqualTo(1700000000000L)
        assertThat((stamped.parent as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
        assertThat(stamped.children).allSatisfy {
            assertThat((it as OrderRequest.Limit).expiresAt).isEqualTo(1700000000000L)
        }
    }
}
