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
}
