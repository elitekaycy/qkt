package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.PerSymbolCalendar
import com.qkt.common.TradingCalendar
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A replayed symbol's venue takes orders inside its session. A tick replayed from a recording is judged at its stamp;
 * one synthesized from a bar is judged by its bar, whose open, extremes and close carry made-up times.
 */
class ReplaySessionsTest {
    private val stock = requireNotNull(TradingCalendar.named("nyse[09:30-15:45]"))
    private val calendars =
        mapOf("X:AAPL" to stock, "X:EURUSD" to TradingCalendar.fxDefault(), "X:BTCUSD" to TradingCalendar.crypto())
    private val calendar = PerSymbolCalendar(stock, calendars.keys.toList(), calendars::getValue)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `a recorded tick is judged at its own time`() {
        val sessions = ReplaySessions(calendar)

        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T11:00:00Z"))).isFalse()
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T13:29:59.999Z"))).isFalse()
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T13:30:00Z"))).isTrue()
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T13:30:30Z"))).isTrue()
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T19:44:59Z"))).isTrue()
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T19:45:00Z"))).isFalse()
        // Saturday: no stock session, crypto trades, the FX week is shut.
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-31T15:00:00Z"))).isFalse()
        assertThat(sessions.acceptsOrders("X:BTCUSD", ms("2026-10-31T15:00:00Z"))).isTrue()
        assertThat(sessions.acceptsOrders("X:EURUSD", ms("2026-10-31T15:00:00Z"))).isFalse()
    }

    @Test
    fun `a bar trades when any minute of it is in session, whatever its synthetic stamps`() {
        val fills =
            BarFills(
                setOf("X:AAPL", "X:EURUSD"),
                windows = mapOf("X:AAPL" to TimeWindow.ONE_MINUTE, "X:EURUSD" to TimeWindow.parse("4h")),
            )
        val sessions = ReplaySessions(calendar, fills)

        // The FX week closes Friday 17:00 New York (21:00 UTC in summer): the Friday 20:00-24:00 UTC bar's close is
        // stamped 23:59:59.999 but the bar traded, so its levels fill as they did before the session gate.
        assertThat(sessions.acceptsOrders("X:EURUSD", ms("2024-06-14T23:59:59.999Z"))).isTrue()
        assertThat(sessions.acceptsOrders("X:EURUSD", ms("2024-06-15T08:00:00Z"))).isFalse()
        // The Sunday 20:00-24:00 bar opens before the 21:00 UTC open: its open tick is stamped 20:00.
        assertThat(sessions.acceptsOrders("X:EURUSD", ms("2024-06-16T20:00:00Z"))).isTrue()
        // A one-minute pre-market stock bar is wholly outside the session; the 09:30 bar is inside.
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T13:29:45Z"))).isFalse()
        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-28T13:30:15Z"))).isTrue()
    }

    @Test
    fun `a run without calendars accepts every order`() {
        val sessions = ReplaySessions(TradingCalendar.crypto())

        assertThat(sessions.acceptsOrders("X:AAPL", ms("2026-10-31T15:00:00Z"))).isTrue()
    }
}
