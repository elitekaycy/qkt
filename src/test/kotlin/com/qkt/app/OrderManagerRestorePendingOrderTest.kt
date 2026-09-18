package com.qkt.app

import com.qkt.app.OrderManagerRestoreFixtures.RecordingBroker
import com.qkt.app.OrderManagerRestoreFixtures.newBus
import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.LogBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.BracketPair
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerRestorePendingOrderTest {
    @Test
    fun `restore is a no-op when nothing was persisted`(
        @TempDir tmp: Path,
    ) {
        val broker = RecordingBroker(LogBroker(newBus(), FixedClock(0L)))
        val om = OrderManager(broker, newBus(), MarketPriceTracker(), FixedClock(0L), FileStatePersistor(tmp))

        om.restore(listOf("alpha"))

        assertThat(broker.recovered).isEmpty()
    }

    @Test
    fun `restore adopts a single pending order and enforces its GTD expiry`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val request =
            OrderRequest.Stop(
                id = "entry-stop",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTD,
                timestamp = 0L,
                strategyId = "alpha",
                expiresAt = 1_000L,
            )
        persistor.savePendingOrders("alpha", mapOf(request.id to request))
        persistor.saveBracketPairs(
            "alpha",
            listOf(BracketPair(request.id, "entry-stop-sl", "entry-stop-tp", null)),
        )
        val clock = FixedClock(2_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = RecordingBroker(LogBroker(bus, clock))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        om.restore(listOf("alpha"))

        assertThat(broker.recovered.map { it.id }).containsExactly("entry-stop")
        assertThat(om.getOrder("entry-stop")?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.activeEntryOrderCount("alpha", "XAUUSD")).isEqualTo(1)
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("1990"), clock.now())))
        assertThat(om.getOrder("entry-stop")).isNull()
        assertThat(om.activeEntryOrderCount("alpha", "XAUUSD")).isZero()
        assertThat(persistor.loadPendingOrders("alpha")).isEmpty()
    }

    @Test
    fun `restore retires a working order the venue cannot account for`(
        @TempDir tmp: Path,
    ) {
        // bot1 carried 204 pre-#1048 bracket wrappers whose positions had closed weeks earlier:
        // no pending ticket, no position, nothing for the broker to track. Restoring them as
        // WORKING kept their exposure registered for the whole session. The venue's answer is
        // authoritative: an unaccounted order is retired through the normal cancel path.
        val persistor = FileStatePersistor(tmp)
        val stale =
            OrderRequest.Stop(
                id = "stale-entry",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.45"),
                stopPrice = BigDecimal("4350"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val live =
            OrderRequest.Stop(
                id = "live-entry",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf(stale.id to stale, live.id to live))
        val clock = FixedClock(2_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = RecordingBroker(LogBroker(bus, clock)).apply { unaccounted += "stale-entry" }
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        om.restore(listOf("alpha"))

        assertThat(broker.recovered.map { it.id }).containsExactlyInAnyOrder("stale-entry", "live-entry")
        assertThat(om.getOrder("live-entry")?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.getOrder("stale-entry")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(om.activeEntryOrderCount("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(persistor.loadPendingOrders("alpha").keys).containsExactly("live-entry")
    }

    @Test
    fun `restore drops a pending order whose venue is no longer configured`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val stale =
            OrderRequest.Market(
                id = "entry-stale",
                symbol = "ICM_S01:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val live =
            OrderRequest.Limit(
                id = "entry-live",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                limitPrice = BigDecimal("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf(stale.id to stale, live.id to live))
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fake = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
        val broker =
            object : Broker by RecordingBroker(fake) {
                override fun supports(symbol: String): Boolean = !symbol.startsWith("ICM_S01:")
            }
        val manager = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor)

        manager.restore(listOf("alpha"))

        assertThat(manager.getOrder(stale.id)).isNull()
        assertThat(manager.getOrder(live.id)?.state).isEqualTo(OrderState.WORKING)
        assertThat(persistor.loadPendingOrders("alpha").keys).containsExactly(live.id)
    }
}
