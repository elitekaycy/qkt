package com.qkt.parity

import com.qkt.app.LegIntentPlanner
import com.qkt.app.OrderManager
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.mt5.convertAlreadyCrossedStop
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AlreadyCrossedStopParityTest {
    @Test
    fun `engine-held and MT5-shaped crossed stops make the same market decision`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker =
            FakeBroker(
                bus,
                clock,
                capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT),
            )
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val stop =
            OrderRequest.Stop(
                id = "breakout",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )

        manager.submit(stop)
        bus.publish(
            TickEvent(
                Tick(
                    symbol = stop.symbol,
                    price = BigDecimal("1.1049"),
                    timestamp = 1L,
                    bid = BigDecimal("1.1048"),
                    ask = BigDecimal("1.1051"),
                ),
            ),
        )
        val engineHeldDecision = broker.submits.single()
        // The live broker receives the request after OrderManager has planned its leg intent,
        // so the MT5-shaped conversion must start from the same planned request.
        val planned = LegIntentPlanner.plan(stop, PositionAccountingMode.UNKNOWN)
        val liveShapedDecision = convertAlreadyCrossedStop(planned, currentExecPrice = BigDecimal("1.1051"))

        assertThat(engineHeldDecision).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(liveShapedDecision).isEqualTo(engineHeldDecision)
    }

    @Test
    fun `MT5-shaped crossed StopLimit activates the same plain limit as engine-held`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker =
            FakeBroker(
                bus,
                clock,
                capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT),
            )
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val stopLimit =
            OrderRequest.StopLimit(
                id = "breakout-limit",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                limitPrice = BigDecimal("1.1040"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )

        manager.submit(stopLimit)
        bus.publish(
            TickEvent(
                Tick(
                    symbol = stopLimit.symbol,
                    price = BigDecimal("1.1049"),
                    timestamp = 1L,
                    bid = BigDecimal("1.1048"),
                    ask = BigDecimal("1.1051"),
                ),
            ),
        )
        val engineHeldDecision = broker.submits.single()
        val liveShapedDecision = convertAlreadyCrossedStop(stopLimit, currentExecPrice = BigDecimal("1.1051"))

        assertThat(engineHeldDecision).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat(liveShapedDecision).isEqualTo(engineHeldDecision)
    }

    @Test
    fun `stop equality counts as crossed for both sides`() {
        val buy =
            OrderRequest.Stop(
                id = "buy",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val sell = buy.copy(id = "sell", side = Side.SELL)

        assertThat(convertAlreadyCrossedStop(buy, buy.stopPrice)).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(convertAlreadyCrossedStop(sell, sell.stopPrice)).isInstanceOf(OrderRequest.Market::class.java)
    }
}
