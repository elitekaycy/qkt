package com.qkt.app.order

import com.qkt.app.OrderManagerFixtures
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AttachedBracketCompletionTest {
    private val book = OrderBook(isReferenced = { false }, reclaim = {})
    private val brackets = BracketBook()
    private val closeTickets = EngineHeldCloseTickets()
    private val exposure = PendingExposureBook(book)
    private val ops = BookOrderOps(book)
    private val completion = AttachedBracketCompletion(book, brackets, closeTickets, exposure, FixedClock(9L), ops)
    private val bracket = OrderManagerFixtures.bracket("b1", "e1")

    private fun put(
        id: String,
        request: OrderRequest,
        state: OrderState,
        parent: String? = null,
        children: List<String> = emptyList(),
        ticket: String? = null,
        filled: String = "0",
    ) = book.put(
        ManagedOrder(
            id = id,
            request = request,
            state = state,
            brokerOrderId = ticket,
            cumulativeFilledQuantity = BigDecimal(filled),
            parentClientOrderId = parent,
            childClientOrderIds = children,
            createdAt = 0L,
            lastUpdatedAt = 0L,
        ),
    )

    private fun stop(id: String) =
        OrderRequest.Stop(id, "EURUSD", Side.SELL, BigDecimal.ONE, BigDecimal("1.09"), TimeInForce.GTC, 0L)

    private fun fill(
        id: String,
        quantity: String,
        ticket: String? = "T1",
    ) = BrokerEvent.OrderFilled(
        clientOrderId = id,
        brokerOrderId = ticket,
        symbol = "EURUSD",
        side = Side.SELL,
        price = BigDecimal("1.09"),
        quantity = BigDecimal(quantity),
        timestamp = 0L,
    )

    private fun wrapper(state: OrderState = OrderState.WORKING) {
        put("b1", bracket, state, children = listOf("e1", "b1-sl"))
        exposure.register(bracket)
    }

    private fun entry(
        state: OrderState = OrderState.FILLED,
        filled: String = "0",
        parent: String? = "b1",
    ) = put("e1", bracket.copy(id = "e1"), state, parent = parent, ticket = "T1", filled = filled)

    @Test
    fun `a restored engine stop closing the whole position releases every stop held on its ticket`() {
        entry(parent = null)
        put("b1-sl", stop("b1-sl"), OrderState.PENDING)
        put("other-sl", stop("other-sl"), OrderState.PENDING)
        closeTickets["other-sl"] = "T1"

        completion.onEngineExit(fill("b1-sl", "0.4"))
        assertThat(ops.cancelled).isEmpty()
        assertThat(brackets.venueClosedQuantityByEntry["e1"]).isEqualByComparingTo("0.4")

        completion.onEngineExit(fill("b1-sl", "0.6"))
        assertThat(ops.cancelled).containsExactly("other-sl")
        assertThat(brackets.venueClosedQuantityByEntry).isEmpty()
    }

    @Test
    fun `a restored engine exit with no ticket, or a ticket no attached entry holds, changes nothing`() {
        entry(parent = null)
        put("b1-sl", stop("b1-sl"), OrderState.PENDING)
        closeTickets["b1-sl"] = "T1"

        completion.onEngineExit(fill("b1-sl", "1", ticket = " "))
        completion.onEngineExit(fill("b1-sl", "1", ticket = "T9"))
        completion.onEngineExit(fill("b1-close", "1"))

        assertThat(ops.cancelled).isEmpty()
        assertThat(brackets.venueClosedQuantityByEntry).isEmpty()
    }

    @Test
    fun `an engine exit of a live wrapper completes it and cancels its held children`() {
        wrapper()
        entry(filled = "1")
        put("b1-sl", stop("b1-sl"), OrderState.PENDING, parent = "b1")
        put("b1-tp", stop("b1-tp"), OrderState.FILLED, parent = "b1")

        completion.onEngineExit(fill("b1-tp", "1"))

        assertThat(ops.cancelled).containsExactly("b1-sl")
        assertThat(book["b1"]!!.state).isEqualTo(OrderState.FILLED)
        assertThat(book["b1"]!!.lastUpdatedAt).isEqualTo(9L)
        assertThat("b1" in exposure).isFalse()
    }

    @Test
    fun `an engine exit whose entry record is gone falls back to the bracket's size`() {
        wrapper()
        put("b1-tp", stop("b1-tp"), OrderState.FILLED, parent = "b1")

        completion.onEngineExit(fill("b1-tp", "0.5"))
        assertThat(book["b1"]!!.state).isEqualTo(OrderState.WORKING)

        completion.onEngineExit(fill("b1-tp", "0.5"))
        assertThat(book["b1"]!!.state).isEqualTo(OrderState.FILLED)
    }

    @Test
    fun `an engine exit of a finished wrapper or a non attached entry is ignored`() {
        wrapper(OrderState.CANCELLED)
        put("b1-tp", stop("b1-tp"), OrderState.FILLED, parent = "b1")
        completion.onEngineExit(fill("b1-tp", "1"))
        assertThat(book["b1"]!!.state).isEqualTo(OrderState.CANCELLED)

        wrapper()
        put("e1", bracket.entry, OrderState.FILLED, parent = "b1")
        completion.onEngineExit(fill("b1-tp", "1"))
        assertThat(book["b1"]!!.state).isEqualTo(OrderState.WORKING)
        assertThat(brackets.venueClosedQuantityByEntry).isEmpty()
    }

    @Test
    fun `a venue close completes the wrapper only once the filled entry is fully closed`() {
        wrapper()
        put("b1-sl", stop("b1-sl"), OrderState.WORKING, parent = "b1")
        entry(state = OrderState.WORKING)
        completion.onVenueClose(fill("e1", "1"))
        assertThat(brackets.venueClosedQuantityByEntry).isEmpty()

        entry()
        completion.onVenueClose(fill("e1", "1", ticket = null))
        // The stop is still live at the venue, so the wrapper waits for its own terminal event.
        assertThat(book["b1"]!!.state).isEqualTo(OrderState.WORKING)
        assertThat(ops.cancelled).isEmpty()
    }

    @Test
    fun `an entry that ended unfilled ends its wrapper and cancels the children it armed`() {
        wrapper()
        put("b1-sl", stop("b1-sl"), OrderState.CREATED, parent = "b1")
        entry(state = OrderState.REJECTED)

        completion.onEntryEnded("e1", OrderState.REJECTED)

        assertThat(ops.cancelled).containsExactly("b1-sl")
        assertThat(book["b1"]!!.state).isEqualTo(OrderState.REJECTED)
        assertThat("b1" in exposure).isFalse()
    }

    @Test
    fun `an ended entry leaves its wrapper alone while a child still works at the venue`() {
        wrapper()
        put("b1-sl", stop("b1-sl"), OrderState.WORKING, parent = "b1")
        entry(state = OrderState.CANCELLED)

        completion.onEntryEnded("e1", OrderState.CANCELLED)

        assertThat(book["b1"]!!.state).isEqualTo(OrderState.WORKING)
        assertThat(ops.cancelled).isEmpty()
    }

    @Test
    fun `an ended entry that is not an attached bracket entry changes nothing`() {
        completion.onEntryEnded("missing", OrderState.CANCELLED)
        put("plain", stop("plain"), OrderState.CANCELLED)
        completion.onEntryEnded("plain", OrderState.CANCELLED)
        entry(state = OrderState.CANCELLED, parent = null)
        completion.onEntryEnded("e1", OrderState.CANCELLED)
        entry(state = OrderState.CANCELLED, parent = "gone")
        completion.onEntryEnded("e1", OrderState.CANCELLED)
        wrapper(OrderState.FILLED)
        entry(state = OrderState.CANCELLED)
        completion.onEntryEnded("e1", OrderState.CANCELLED)

        assertThat(book["b1"]!!.state).isEqualTo(OrderState.FILLED)
        assertThat(ops.cancelled).isEmpty()
    }
}
