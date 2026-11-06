package com.qkt.risk.book

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Broker-side outcomes on the bus free a reservation; gross cap 0.3 x 1,000 = 300 fits one 200 entry. */
class BookReservationWiringTest {
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val controller =
        BookRiskController(
            config = BookRiskConfig(limits = BookLimits(maxGrossExposure = BigDecimal("0.3"))),
            capital = BigDecimal("1000"),
        ).also {
            val flat = Exposure(BigDecimal.ZERO, BigDecimal.ZERO, emptyMap())
            it.onSample(BookSnapshot(1L, BigDecimal("1000"), flat, emptyMap()))
            wireBookReservations(bus, it)
        }

    @Test
    fun `a venue rejection frees the rejected order's reservation`() {
        controller.checkAndReserve(bookReservationKey("book:a", "ORD-0"), "A", BigDecimal("200"))
        assertThat(controller.pendingReservations()).isEqualTo(1)

        bus.publish(BrokerEvent.OrderRejected("ORD-0", "ORD-0", "no money", strategyId = "book:a", timestamp = 1L))

        assertThat(controller.pendingReservations()).isZero()
        assertThat(controller.checkAndReserve(bookReservationKey("book:b", "ORD-0"), "B", BigDecimal("200"))).isNull()
    }

    @Test
    fun `a venue cancel frees the cancelled order's reservation`() {
        controller.checkAndReserve(bookReservationKey("book:a", "ORD-1"), "A", BigDecimal("200"))

        bus.publish(BrokerEvent.OrderCancelled("ORD-1", "ORD-1", "user cancel", strategyId = "book:a", timestamp = 1L))

        assertThat(controller.pendingReservations()).isZero()
    }
}
