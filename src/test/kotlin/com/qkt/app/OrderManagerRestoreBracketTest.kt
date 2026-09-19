package com.qkt.app

import com.qkt.app.OrderManagerRestoreFixtures.RecordingBroker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerRestoreBracketTest {
    @Test
    fun `restored fill-anchored fallback bracket places exits from the actual fill`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val entry =
            OrderRequest.Limit(
                id = "entry-limit",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                limitPrice = BigDecimal("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "bracket-1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                entry = entry,
                takeProfit = BigDecimal("120"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("90")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
                takeProfitAst = ChildBy(NumLit(BigDecimal("20"))),
                stopLossAst = ChildBy(NumLit(BigDecimal("10"))),
            )
        persistor.savePendingOrders("alpha", mapOf(entry.id to bracket))
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fake =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        val broker = RecordingBroker(fake)
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = entry.id,
                brokerOrderId = "venue-entry-7",
                symbol = entry.symbol,
                side = entry.side,
                price = BigDecimal("105"),
                quantity = BigDecimal.ONE,
                strategyId = "alpha",
            ),
        )

        assertThat(broker.recovered.map { it.id }).containsExactly(entry.id)
        assertThat(manager.getOrder(entry.id)?.state).isEqualTo(OrderState.FILLED)
        val takeProfit = fake.submits.filterIsInstance<OrderRequest.Limit>().single()
        val stopLoss = fake.submits.filterIsInstance<OrderRequest.Stop>().single()
        assertThat(takeProfit.limitPrice).isEqualByComparingTo("125")
        assertThat(stopLoss.stopPrice).isEqualByComparingTo("95")
        assertThat(takeProfit.strategyId).isEqualTo("alpha")
        assertThat(stopLoss.strategyId).isEqualTo("alpha")
    }

    @Test
    fun `restore of a market-entry bracket before any quote defers its exits to the fill`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val entry =
            OrderRequest.Market(
                id = "entry-market",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "bracket-market",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                entry = entry,
                takeProfit = BigDecimal("120"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("90")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf(entry.id to bracket))
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fake =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP),
            )
        val broker = RecordingBroker(fake)
        // No tick has been seen for XAUUSD: the tracker is empty, as it is right after a deploy.
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))

        assertThat(broker.recovered.map { it.id }).containsExactly(entry.id)
        assertThat(manager.getOrder(entry.id)?.state).isEqualTo(OrderState.WORKING)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = entry.id,
                brokerOrderId = "venue-entry-9",
                symbol = entry.symbol,
                side = entry.side,
                price = BigDecimal("105"),
                quantity = BigDecimal.ONE,
                strategyId = "alpha",
            ),
        )
        assertThat(manager.getOrder(entry.id)?.state).isEqualTo(OrderState.FILLED)
        val takeProfit = fake.submits.filterIsInstance<OrderRequest.Limit>().single()
        val stopLoss = fake.submits.filterIsInstance<OrderRequest.Stop>().single()
        assertThat(takeProfit.limitPrice).isEqualByComparingTo("120")
        assertThat(stopLoss.stopPrice).isEqualByComparingTo("90")
    }
}
