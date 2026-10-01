package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StructureCoordinatorTest {
    private val clock = FixedClock(5L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val emitted = mutableListOf<Signal>()
    private val cancelled = mutableListOf<String>()
    private val book = StructureBook(StructureFixtures.registry, MarketPriceTracker())
    private val shortPut = StructureFixtures.market("s", StructureFixtures.P81, Side.SELL)
    private val longPut = StructureFixtures.market("l", StructureFixtures.P78, Side.BUY)
    private val wing = StructureFixtures.market("w", StructureFixtures.P75, Side.BUY)
    private val farPut = StructureFixtures.market("f", StructureFixtures.P80_30OCT, Side.BUY)

    init {
        StructureCoordinator(bus, clock) { cancelled += it }.bind("st", book) { signal ->
            emitted += signal
            bus.publish(SignalEvent(signal, strategyId = "st"))
        }
    }

    private fun open(vararg legs: OrderRequest) =
        bus.publish(SignalEvent(Signal.SubmitGroup("ps-1", "ps", legs.toList()), strategyId = "st"))

    private fun filled(
        leg: OrderRequest,
        strategyId: String = "st",
    ) = bus.publish(
        BrokerEvent.OrderFilled(
            leg.id,
            leg.id,
            leg.symbol,
            leg.side,
            BigDecimal.TEN,
            leg.quantity,
            strategyId = strategyId,
        ),
    )

    private fun cancelled(id: String) = bus.publish(BrokerEvent.OrderCancelled(id, id, "no bid", strategyId = "st"))

    private fun unwinds() = emitted.filterIsInstance<Signal.SubmitGroup>()

    @Test
    fun `a failed leg unwinds the filled legs as one forced group, shorts bought back first`() {
        open(longPut, wing, shortPut)
        filled(longPut)
        filled(shortPut)

        cancelled("w")

        val unwind = unwinds().single()
        assertThat(unwind.force).isTrue()
        assertThat(unwind.closes).isEqualTo("ps-1")
        assertThat(unwind.alias).isEqualTo("ps")
        assertThat(unwind.requests.map { Triple(it.symbol, it.side, it.quantity) }).containsExactly(
            Triple(StructureFixtures.P81, Side.BUY, BigDecimal("0.1")),
            Triple(StructureFixtures.P78, Side.SELL, BigDecimal("0.1")),
        )
        assertThat(unwind.requests).allMatch { it.strategyId == "st" && it.timestamp == 5L }
        assertThat(cancelled).isEmpty()
    }

    @Test
    fun `working legs are cancelled, a late fill is unwound, and a cancelled close is sent again`() {
        open(longPut, shortPut)
        cancelled("l")
        assertThat(cancelled).containsExactly("s")

        filled(shortPut)
        val close = unwinds().single().requests.single()
        assertThat(close.side).isEqualTo(Side.BUY)

        cancelled(close.id)

        val retry = unwinds().last().requests.single()
        assertThat(retry.id).isNotEqualTo(close.id)
        assertThat(
            Triple(retry.symbol, retry.side, retry.quantity),
        ).isEqualTo(Triple(close.symbol, close.side, close.quantity))
    }

    @Test
    fun `a fully filled structure and a group refused by risk need nothing`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)
        bus.publish(SignalEvent(Signal.SubmitGroup("qs-1", "qs", listOf(wing)), strategyId = "st"))
        bus.publish(RiskRejectedEvent(wing, "margin"))
        cancelled("w")

        assertThat(emitted).isEmpty()
        assertThat(cancelled).isEmpty()
        assertThat(book.live("qs")).isNull()
    }

    @Test
    fun `a rejected unwind leg is final, never sent again`() {
        open(longPut, shortPut)
        filled(shortPut)
        cancelled("l")
        val close = unwinds().single().requests.single()

        bus.publish(BrokerEvent.OrderRejected(close.id, null, "expired", strategyId = "st"))

        assertThat(unwinds()).hasSize(1)
    }

    @Test
    fun `an expired leg is left to its settlement, the unexpired one is closed`() {
        val nearShort = shortPut.copy(id = "s2")
        val farLong = farPut.copy(id = "f2")
        val pending = StructureFixtures.market("x", StructureFixtures.P75, Side.BUY)
        bus.publish(SignalEvent(Signal.SubmitGroup("qs-1", "qs", listOf(pending, nearShort, farLong)), "st"))
        filled(nearShort)
        filled(farLong)
        clock.time = StructureFixtures.OCT9

        cancelled("x")

        val unwind = unwinds().single().requests.single()
        assertThat(Triple(unwind.symbol, unwind.side, unwind.quantity))
            .isEqualTo(Triple(StructureFixtures.P80_30OCT, Side.SELL, BigDecimal("0.1")))
    }

    @Test
    fun `fills and cancels of another strategy's orders are not this strategy's`() {
        open(longPut, shortPut)

        filled(longPut, strategyId = "other")
        bus.publish(BrokerEvent.OrderCancelled("s", "s", "no bid", strategyId = "other"))
        assertThat(emitted).isEmpty()
        assertThat(cancelled).isEmpty()

        cancelled("s")

        assertThat(cancelled).containsExactly("l")
        assertThat(unwinds()).isEmpty()
    }

    @Test
    fun `an expiry print settles the structure's legs on that contract`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)
        clock.time = StructureFixtures.OCT9

        listOf(shortPut, longPut).forEach { leg ->
            bus.publish(
                BrokerEvent.OrderFilled(
                    "expiry:${leg.symbol}:st",
                    null,
                    leg.symbol,
                    if (leg.side == Side.BUY) Side.SELL else Side.BUY,
                    BigDecimal.ZERO,
                    leg.quantity,
                    strategyId = "st",
                    updatesOrderExecution = false,
                    exitReason = ExitReason.EXPIRY,
                ),
            )
        }

        assertThat(book.live("ps")).isNull()
        assertThat(emitted).isEmpty()
    }
}
