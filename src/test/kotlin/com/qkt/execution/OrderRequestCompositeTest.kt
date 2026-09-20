package com.qkt.execution

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestCompositeTest {
    @Test
    fun `StandaloneOCO carries two legs`() {
        val l1 =
            OrderRequest.Limit(
                id = "l1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        val l2 =
            OrderRequest.Limit(
                id = "l2",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.20"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = l1,
                leg2 = l2,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(oco.leg1).isSameAs(l1)
        assertThat(oco.leg2).isSameAs(l2)
    }

    @Test
    fun `OTO requires at least one child`() {
        val parent =
            OrderRequest.Market(
                id = "m1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThatThrownBy {
            OrderRequest.OTO(
                id = "oto1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = emptyList(),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one child")
    }

    @Test
    fun `Bracket rejects equal tp and sl`() {
        val entry =
            OrderRequest.Limit(
                id = "e1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("4500"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThatThrownBy {
            OrderRequest.Bracket(
                id = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = entry,
                takeProfit = Money.of("4500"),
                stopLoss = StopLossSpec.Fixed(Money.of("4500")),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ScaleOut total fraction must not exceed 1`() {
        val entry =
            OrderRequest.Market(
                id = "m1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("3"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThatThrownBy {
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("3"),
                basis = entry,
                legs =
                    listOf(
                        ScaleOutLeg(Money.of("90000"), Money.of("0.7")),
                        ScaleOutLeg(Money.of("100000"), Money.of("0.7")),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fraction")
    }

    @Test
    fun `TimeExit constructs with deadline`() {
        val entry =
            OrderRequest.Limit(
                id = "e1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        val te =
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("1"),
                target = entry,
                deadline = java.time.Instant.parse("2030-01-01T00:00:00Z"),
                onExpiry = ExpiryAction.CANCEL,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(te.onExpiry).isEqualTo(ExpiryAction.CANCEL)
    }
}
