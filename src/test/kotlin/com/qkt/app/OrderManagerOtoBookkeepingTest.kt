package com.qkt.app

import com.qkt.app.OrderManagerOtoFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerOtoBookkeepingTest {
    @Test
    fun `OTO persists unarmed children until the parent fills`(
        @TempDir tmp: Path,
    ) {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val persistor = FileStatePersistor(tmp)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)
        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val child =
            OrderRequest.Limit(
                id = "c1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("110"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val oto =
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )

        om.submit(oto)

        assertThat(persistor.loadPendingOrders("alpha")).containsExactlyEntriesOf(mapOf("p1" to oto))

        broker.emitFill(parent, price = Money.of("100"))

        assertThat(persistor.loadPendingOrders("alpha")).containsExactlyEntriesOf(mapOf("c1" to child))
        assertThat(broker.submits.map { it.id }).containsExactly("p1", "c1")
    }

    @Test
    fun `OTO counts its active parent once and excludes its protective child`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                isRiskReducingForHalt = { request -> request.side == Side.SELL },
            )

        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val child =
            OrderRequest.Limit(
                id = "c1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("110"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        om.submit(
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        )
        assertThat(om.activeEntryOrderCount("alpha", "X")).isEqualTo(1)

        broker.emitFill(parent, price = Money.of("100"))

        assertThat(broker.submits.map { it.id }).containsExactlyInAnyOrder("p1", "c1")
        assertThat(om.activeEntryOrderCount("alpha", "X")).isZero()
    }
}
