package com.qkt.app

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExitReason
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Structure legs at and past their expiry. */
internal class StructureCoordinatorExpiryTest : StructureCoordinatorHarness() {
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

    @Test
    fun `the first tick at expiry settles held legs from the clock`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)
        clock.time = StructureFixtures.OCT9

        bus.publish(TickEvent(Tick("BTC", BigDecimal.ONE, StructureFixtures.OCT9)))

        assertThat(book.live("ps")).isNull()
    }
}
