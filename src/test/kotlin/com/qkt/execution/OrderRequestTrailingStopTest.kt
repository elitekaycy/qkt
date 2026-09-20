package com.qkt.execution

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestTrailingStopTest {
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
}
