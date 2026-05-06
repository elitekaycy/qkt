package com.qkt.execution

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestTest {
    @Test
    fun `Market constructs with required fields`() {
        val m =
            OrderRequest.Market(
                id = "o1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(m.symbol).isEqualTo("EURUSD")
        assertThat(m.timeInForce).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `Limit carries limitPrice`() {
        val l =
            OrderRequest.Limit(
                id = "o2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(l.limitPrice).isEqualByComparingTo(Money.of("1.10"))
    }

    @Test
    fun `Stop carries stopPrice`() {
        val s =
            OrderRequest.Stop(
                id = "o3",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(s.stopPrice).isEqualByComparingTo(Money.of("1.09"))
    }

    @Test
    fun `StopLimit carries both stopPrice and limitPrice`() {
        val sl =
            OrderRequest.StopLimit(
                id = "o4",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                limitPrice = Money.of("1.085"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(sl.stopPrice).isEqualByComparingTo(Money.of("1.09"))
        assertThat(sl.limitPrice).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `IfTouched MARKET trigger requires no limitPrice`() {
        val it =
            OrderRequest.IfTouched(
                id = "o5",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"),
                onTrigger = TriggerType.MARKET,
                limitPrice = null,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(it.onTrigger).isEqualTo(TriggerType.MARKET)
        assertThat(it.limitPrice).isNull()
    }

    @Test
    fun `IfTouched LIMIT trigger requires limitPrice`() {
        assertThatThrownBy {
            OrderRequest.IfTouched(
                id = "o6",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"),
                onTrigger = TriggerType.LIMIT,
                limitPrice = null,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("limitPrice")
    }

    @Test
    fun `quantity must be positive`() {
        assertThatThrownBy {
            OrderRequest.Market(
                id = "o7",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("quantity")
    }

    @Test
    fun `TrailingStop ABSOLUTE constructs`() {
        val ts =
            OrderRequest.TrailingStop(
                id = "t1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("0.005"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(ts.trailAmount).isEqualByComparingTo(Money.of("0.005"))
        assertThat(ts.trailMode).isEqualTo(TrailMode.ABSOLUTE)
    }

    @Test
    fun `TrailingStop PERCENT rejects values over 100`() {
        assertThatThrownBy {
            OrderRequest.TrailingStop(
                id = "t2",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("150"),
                trailMode = TrailMode.PERCENT,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("100")
    }

    @Test
    fun `TrailingStopLimit constructs with offset`() {
        val tsl =
            OrderRequest.TrailingStopLimit(
                id = "t3",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("0.005"),
                trailMode = TrailMode.ABSOLUTE,
                limitOffset = Money.of("0.001"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(tsl.limitOffset).isEqualByComparingTo(Money.of("0.001"))
    }

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
                stopLoss = Money.of("4500"),
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
