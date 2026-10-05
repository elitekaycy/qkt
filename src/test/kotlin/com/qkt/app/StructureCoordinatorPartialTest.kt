package com.qkt.app

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A structure's legs filled in slices: every slice is held, closed and unwound, never only the last one. */
internal class StructureCoordinatorPartialTest : StructureCoordinatorHarness() {
    private fun slice(
        leg: OrderRequest,
        quantity: String,
        cumulative: String,
        price: String = "10",
    ) = bus.publish(
        BrokerEvent.OrderPartiallyFilled(
            leg.id,
            leg.id,
            leg.symbol,
            leg.side,
            BigDecimal(price),
            BigDecimal(quantity),
            BigDecimal(cumulative),
            strategyId = "st",
        ),
    )

    private fun lastSlice(
        leg: OrderRequest,
        quantity: String,
        price: String = "10",
    ) = bus.publish(
        BrokerEvent.OrderFilled(leg.id, leg.id, leg.symbol, leg.side, BigDecimal(price), BigDecimal(quantity), "st"),
    )

    private fun held(symbol: String) =
        requireNotNull(book.live("ps")).legs.single { it.symbol == symbol }.let { it.heldQuantity to it.entryPrice }

    @Test
    fun `an opening leg filled in slices holds every slice at their average price`() {
        open(longPut, shortPut)
        slice(longPut, "0.04", "0.04", price = "10")
        lastSlice(longPut, "0.06", price = "20")

        val (quantity, price) = held(StructureFixtures.P78)
        assertThat(quantity).isEqualByComparingTo("0.1")
        assertThat(price).isEqualByComparingTo("16")
    }

    @Test
    fun `an opening leg cancelled after a partial fill unwinds what it filled`() {
        open(longPut, shortPut)
        filled(shortPut)
        slice(longPut, "0.04", "0.04")

        cancelled("l")

        val unwind = unwinds().single()
        assertThat(unwind.requests.map { Triple(it.symbol, it.side, it.quantity) }).containsExactlyInAnyOrder(
            Triple(StructureFixtures.P81, Side.BUY, BigDecimal("0.1")),
            Triple(StructureFixtures.P78, Side.SELL, BigDecimal("0.04")),
        )
    }

    @Test
    fun `a closing leg cancelled after a partial fill is sent again only for what it still holds`() {
        open(longPut, shortPut)
        filled(shortPut)
        cancelled("l")
        val close = unwinds().single().requests.single()
        slice(close, "0.04", "0.04")

        cancelled(close.id)

        val retry = unwinds().last().requests.single()
        assertThat(retry.id).isNotEqualTo(close.id)
        assertThat(retry.quantity).isEqualByComparingTo("0.06")
        assertThat(held(StructureFixtures.P81).first).isEqualByComparingTo("-0.06")
    }

    @Test
    fun `a closing leg filled in slices leaves nothing held`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)
        bus.publish(
            com.qkt.events.SignalEvent(
                com.qkt.strategy.Signal.SubmitGroup(
                    "c-1",
                    "ps",
                    listOf(StructureFixtures.market("c-l", StructureFixtures.P78, Side.SELL)),
                    closes = "ps-1",
                ),
                strategyId = "st",
            ),
        )
        val close = StructureFixtures.market("c-l", StructureFixtures.P78, Side.SELL)
        slice(close, "0.04", "0.04")
        lastSlice(close, "0.06")

        assertThat(held(StructureFixtures.P78).first).isEqualByComparingTo("0")
    }

    @Test
    fun `a flatten filled in slices closes every slice from the legs it trades against`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)
        val flatten = StructureFixtures.market("flat-1", StructureFixtures.P78, Side.SELL)

        slice(flatten, "0.04", "0.04")
        lastSlice(flatten, "0.06")

        assertThat(held(StructureFixtures.P78).first).isEqualByComparingTo("0")
    }
}
