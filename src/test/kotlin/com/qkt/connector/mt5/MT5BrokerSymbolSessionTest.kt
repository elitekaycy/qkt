package com.qkt.connector.mt5

import com.qkt.broker.SymbolSessionProvider
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [MT5Broker] answers per-symbol sessions from its profile's calendars; nothing here talks to a gateway. */
class MT5BrokerSymbolSessionTest {
    private val saturdayMs = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()

    @Test
    fun `an account trading gold and bitcoin reports each symbol's own session`() {
        val clock = FixedClock(time = saturdayMs)
        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = "http://127.0.0.1:9",
                symbolCalendars =
                    MT5DefaultProfiles.exness.symbolCalendars.overriddenBy(
                        listOf(SymbolCalendars.Rule("BTC*", TradingCalendar.crypto())),
                    ),
            )
        val broker = MT5Broker(profile, EventBus(clock, MonotonicSequenceGenerator()), clock)
        try {
            assertThat(broker).isInstanceOf(SymbolSessionProvider::class.java)
            assertThat(broker.symbolInSession("EXNESS:XAUUSD", saturdayMs)).isFalse()
            assertThat(broker.symbolInSession("EXNESS:BTCUSD", saturdayMs)).isTrue()
            // The venue-wide answer is open because one calendar is: the reason the gate asks per symbol.
            assertThat(broker.marketOpen(saturdayMs)).isTrue()
        } finally {
            broker.shutdown()
        }
    }
}
