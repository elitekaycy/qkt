package com.qkt.app

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.strategy.Signal
import com.qkt.strategy.StructureState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class StructureCoordinatorTest : StructureCoordinatorHarness() {
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
    fun `a fill of an order no structure sent closes the legs it trades against`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)

        filled(StructureFixtures.market("flat-1", StructureFixtures.P81, Side.BUY))
        filled(StructureFixtures.market("flat-2", StructureFixtures.P78, Side.SELL))

        assertThat(book.live("ps")).isNull()
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `an unwind the venue rejected leaves the structure idle, so a later close can end it`() {
        open(longPut, shortPut)
        filled(shortPut)
        cancelled("l")
        val close = unwinds().single().requests.single()

        bus.publish(BrokerEvent.OrderRejected(close.id, null, "rejected", strategyId = "st"))

        val ps = requireNotNull(book.live("ps"))
        assertThat(ps.state).isEqualTo(StructureState.UNWINDING)
        assertThat(ps.working).isFalse()
        val again =
            requireNotNull(
                com.qkt.dsl.compile.StructureCloses
                    .endAll(book, "st", 5L, ids)
                    .single() as? Signal.SubmitGroup,
            )
        assertThat(again.requests.map { it.symbol to it.side }).containsExactly(StructureFixtures.P81 to Side.BUY)
    }
}
