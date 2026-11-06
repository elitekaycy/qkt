package com.qkt.app.order

import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VenuePositionProtectionTest {
    private class Venue(
        override val capabilities: Set<OrderTypeCapability>,
        val answer: () -> SubmitAck,
    ) : Broker {
        val modifies = mutableListOf<Triple<String, BigDecimal?, BigDecimal?>>()
        override val name = "venue"

        override fun submit(request: OrderRequest) = error("unused")

        override fun cancel(orderId: String) {}

        override fun modifyPositionAsync(
            ticket: String,
            sl: BigDecimal?,
            tp: BigDecimal?,
            onResult: (SubmitAck) -> Unit,
        ) {
            modifies += Triple(ticket, sl, tp)
            onResult(answer())
        }
    }

    private val bus = newBus()
    private val ops = RecordingOrderOps()
    private val bracketFallbacks = mutableListOf<String>()

    private fun protection(
        venue: Venue,
        ticketOf: (OrderRequest) -> String? = { "T-1" },
    ) = VenuePositionProtection(
        venue,
        bus,
        ops,
        ticketOf,
        armStackFallbackStop = { _, _, _, _ -> null },
        armBracketFallback = { wrapperId, _, _, ticket -> bracketFallbacks += "$wrapperId@$ticket" },
    ).also { p -> bus.subscribe<BrokerEvent.PositionModificationCompleted> { p.onCompleted(it) } }

    private val stopPrice = BigDecimal("1990")
    private val gtc = TimeInForce.GTC

    private val stop =
        ManagedOrder(
            id = "b-sl",
            request = OrderRequest.Stop("b-sl", "XAUUSD", Side.SELL, BigDecimal.ONE, stopPrice, gtc, 0L, "alpha"),
            state = OrderState.WORKING,
            createdAt = 0L,
            lastUpdatedAt = 0L,
        )

    private val modifiable = setOf(OrderTypeCapability.POSITION_MODIFY)

    @Test
    fun `a refused stop ratchet is alerted while the engine trigger stays active`() {
        val venue = Venue(modifiable) { SubmitAck("T-1", "T-1", accepted = false, rejectReason = "too close") }

        protection(venue).ratchet(stop, BigDecimal("1995"), "trail-2")

        assertThat(venue.modifies).containsExactly(Triple("T-1", BigDecimal("1995"), null))
        assertThat(ops.alerts).containsExactly(
            "alpha: venue rejected stop ratchet b-sl at 1995 for ticket T-1: too close; engine trigger remains active",
        )
    }

    @Test
    fun `an accepted ratchet needs no alert`() {
        val venue = Venue(modifiable) { SubmitAck("T-1", "T-1", accepted = true) }

        protection(venue).ratchet(stop, BigDecimal("1995"), "trail-2")

        assertThat(venue.modifies).hasSize(1)
        assertThat(ops.alerts).isEmpty()
    }

    @Test
    fun `no ratchet is sent without position modify or without a ticket to modify`() {
        val unmodifiable = Venue(emptySet()) { error("not sent") }
        val modifiableWithoutTicket = Venue(modifiable) { error("not sent") }

        protection(unmodifiable).ratchet(stop, BigDecimal("1995"), "t")
        protection(modifiableWithoutTicket, ticketOf = { null }).ratchet(stop, BigDecimal("1995"), "t")

        assertThat(unmodifiable.modifies).isEmpty()
        assertThat(modifiableWithoutTicket.modifies).isEmpty()
    }

    @Test
    fun `a modify that throws is treated as a refusal and arms the bracket fallback`() {
        val venue = Venue(modifiable) { error("gateway timeout") }
        val subject = protection(venue)

        subject.attachBracket("op-1", "wrap-1", "T-9", "alpha", null, null, BigDecimal("1990"), BigDecimal("2050"))

        assertThat(bracketFallbacks).containsExactly("wrap-1@T-9")
        assertThat(ops.alerts.single())
            .contains("fill-anchored bracket modify for ticket T-9: gateway timeout")
            .contains("engine-managed protection remains active")
    }

    @Test
    fun `an answer for an unknown operation is ignored`() {
        val subject = protection(Venue(modifiable) { SubmitAck("x", "x", accepted = false) })

        subject.onCompleted(BrokerEvent.PositionModificationCompleted("nobody", "T-0", accepted = false))

        assertThat(ops.alerts).isEmpty()
        assertThat(bracketFallbacks).isEmpty()
    }
}
