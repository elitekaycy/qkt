package com.qkt.broker.mt5

import com.qkt.common.SessionAnchor
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SymbolCalendarsTest {
    private val fx = TradingCalendar.fxDefault()
    private val crypto = TradingCalendar.crypto()
    private val nyse = TradingCalendar.nyse()

    @Test
    fun `first matching pattern wins`() {
        val sc =
            SymbolCalendars(
                listOf(
                    SymbolCalendars.Rule("XAU*", nyse),
                    SymbolCalendars.Rule("*", fx),
                ),
                default = fx,
            )
        assertThat(sc.calendarFor("XAUUSD").name).isEqualTo("nyse")
    }

    @Test
    fun `unmatched symbol falls to default`() {
        val sc = SymbolCalendars(listOf(SymbolCalendars.Rule("BTC*", crypto)), default = fx)
        assertThat(sc.calendarFor("EURUSD").name).isEqualTo("fx")
    }

    @Test
    fun `wildcard star matches everything`() {
        val sc = SymbolCalendars(listOf(SymbolCalendars.Rule("*", crypto)), default = fx)
        assertThat(sc.calendarFor("ANYTHING").name).isEqualTo("crypto")
    }

    @Test
    fun `literal pattern matches only itself`() {
        val sc =
            SymbolCalendars(
                listOf(SymbolCalendars.Rule("US30", nyse), SymbolCalendars.Rule("*", fx)),
                default = fx,
            )
        assertThat(sc.calendarFor("US30").name).isEqualTo("nyse")
        assertThat(sc.calendarFor("US300").name).isEqualTo("fx")
    }

    @Test
    fun `fxDefault resolves every symbol to fx`() {
        val sc = SymbolCalendars.fxDefault()
        assertThat(sc.calendarFor("EURUSD").name).isEqualTo("fx")
        assertThat(sc.calendarFor("BTCUSD").name).isEqualTo("fx")
    }

    @Test
    fun `anyInSession true when one symbol open even if others closed`() {
        val sc =
            SymbolCalendars(
                listOf(
                    SymbolCalendars.Rule("CLOSED*", fake("closed", false)),
                    SymbolCalendars.Rule("OPEN*", fake("open", true)),
                ),
                default = fake("closed", false),
            )
        assertThat(sc.anyInSession(listOf("CLOSEDX", "OPENY"), Instant.EPOCH)).isTrue()
    }

    @Test
    fun `anyInSession false when all closed`() {
        val sc = SymbolCalendars(emptyList(), default = fake("closed", false))
        assertThat(sc.anyInSession(listOf("A", "B"), Instant.EPOCH)).isFalse()
    }

    private fun fake(
        n: String,
        inSession: Boolean,
    ): TradingCalendar =
        object : TradingCalendar {
            override val name = n

            override fun isInSession(
                symbol: String,
                t: Instant,
            ): Boolean = inSession

            override fun sessionRange(
                symbol: String,
                t: Instant,
            ): TimeRange = TimeRange(Instant.EPOCH, Instant.EPOCH)

            override fun anchorEpochFor(
                anchor: SessionAnchor,
                t: Instant,
            ): Long = 0L

            override fun rangeFor(
                anchor: SessionAnchor,
                anchorEpoch: Long,
            ): TimeRange = TimeRange(Instant.EPOCH, Instant.EPOCH)
        }
}
