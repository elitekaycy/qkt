package com.qkt.broker

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SimulatedVenueRulesTest {
    @Test
    fun `take-profit fill mode reads print, touch and level case-insensitively`() {
        assertThat(TakeProfitFill.fromConfig(" Print ")).isEqualTo(TakeProfitFill.PRINT)
        assertThat(TakeProfitFill.fromConfig("touch")).isEqualTo(TakeProfitFill.PRINT)
        assertThat(TakeProfitFill.fromConfig("LEVEL")).isEqualTo(TakeProfitFill.LEVEL)
    }

    @Test
    fun `an unknown take-profit fill mode is refused naming the valid ones`() {
        assertThatThrownBy { TakeProfitFill.fromConfig("mid") }
            .hasMessage("unknown take-profit fill mode 'mid' (valid: print, level)")
    }

    @Test
    fun `a market order never triggers as a resting order on the mt5 venue`() {
        val market = OrderRequest.Market("m", "X", Side.BUY, Money.of("1"), TimeInForce.GTC, 0L)

        assertThat(mt5Triggered(market, Tick("X", Money.of("1"), 0L))).isFalse()
    }

    @Test
    fun `an engine-managed trailing stop is not a resting type the mt5 venue triggers`() {
        val trailing =
            OrderRequest.TrailingStop(
                "t",
                "X",
                Side.SELL,
                Money.of("1"),
                Money.of("2"),
                TrailMode.ABSOLUTE,
                TimeInForce.GTC,
                0,
            )

        assertThatThrownBy { mt5Triggered(trailing, Tick("X", Money.of("1"), 0L)) }
            .hasMessage("MT5BrokerSimulator.mt5Triggered: unhandled order type TrailingStop")
    }

    @Test
    fun `a sell stop and a sell limit trigger on the bid from opposite sides`() {
        val tick = Tick("X", Money.of("100"), 0L, bid = Money.of("99"), ask = Money.of("101"))
        val sellStop = OrderRequest.Stop("s", "X", Side.SELL, Money.of("1"), Money.of("99"), TimeInForce.GTC, 0L)
        val sellLimit = OrderRequest.Limit("l", "X", Side.SELL, Money.of("1"), Money.of("99.5"), TimeInForce.GTC, 0L)
        val sellTouch =
            OrderRequest.IfTouched(
                id = "i",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                triggerPrice = Money.of("99"),
                onTrigger = com.qkt.execution.TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

        assertThat(mt5Triggered(sellStop, tick)).isTrue()
        assertThat(mt5Triggered(sellLimit, tick)).isFalse()
        assertThat(mt5Triggered(sellTouch, tick)).isTrue()
    }
}
