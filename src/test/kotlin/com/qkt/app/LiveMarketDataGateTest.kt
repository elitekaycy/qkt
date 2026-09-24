package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.broker.SymbolSessionProvider
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import com.qkt.execution.OrderRequest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveMarketDataGateTest {
    private val saturdayMs = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()
    private val wednesdayMs = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()

    private open class Venue(
        private val open: Boolean = true,
    ) : Broker {
        override val name: String = "venue"
        override val capabilities: Set<OrderTypeCapability> = setOf(OrderTypeCapability.MARKET)

        override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) { }

        override fun marketOpen(nowMs: Long): Boolean = open
    }

    private class CalendarVenue(
        calendars: SymbolCalendars,
    ) : Venue(open = true),
        SymbolSessionProvider by SymbolSessionProvider(calendars::inSession)

    private val goldAndBitcoin =
        CalendarVenue(
            SymbolCalendars(
                listOf(SymbolCalendars.Rule("BTC*", TradingCalendar.crypto())),
                TradingCalendar.fxDefault(),
            ),
        )

    @Test
    fun `a venue with per-symbol calendars closes gold at the weekend and keeps bitcoin open`() {
        assertThat(symbolInSession(listOf(goldAndBitcoin), "EXNESS:XAUUSD", saturdayMs)).isFalse()
        assertThat(symbolInSession(listOf(goldAndBitcoin), "EXNESS:BTCUSD", saturdayMs)).isTrue()
        assertThat(symbolInSession(listOf(goldAndBitcoin), "EXNESS:XAUUSD", wednesdayMs)).isTrue()
    }

    @Test
    fun `without per-symbol calendars the venue-wide open flag decides`() {
        assertThat(symbolInSession(listOf(Venue(open = false)), "X", saturdayMs)).isFalse()
        assertThat(symbolInSession(listOf(Venue(open = false), Venue(open = true)), "X", saturdayMs)).isTrue()
    }

    @Test
    fun `a per-symbol venue outranks a venue-wide open flag`() {
        assertThat(symbolInSession(listOf(Venue(open = true), goldAndBitcoin), "EXNESS:XAUUSD", saturdayMs)).isFalse()
    }
}
