package com.qkt.execution

import com.qkt.common.TradingCalendar
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A DAY order lives to the end of its symbol's session, or the next open one once that has ended. */
class DayExpiryTest {
    private val stocks = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse")

    private fun expiry(
        calendar: TradingCalendar,
        at: String,
    ) = Instant.ofEpochMilli(dayExpiryMs(calendar, "AAPL", Instant.parse(at).toEpochMilli())).toString()

    @Test
    fun `a stock CFD's DAY order expires at today's 15_45 New York close`() {
        assertThat(expiry(stocks, "2026-10-26T14:00:00Z")).isEqualTo("2026-10-26T19:45:00Z")
        assertThat(expiry(stocks, "2026-10-26T09:00:00Z")).isEqualTo("2026-10-26T19:45:00Z") // before the open
    }

    @Test
    fun `placed after the close it lives through the next session, over weekends and holidays`() {
        assertThat(expiry(stocks, "2026-10-26T19:45:00Z")).isEqualTo("2026-10-27T19:45:00Z")
        assertThat(expiry(stocks, "2026-10-23T20:00:00Z")).isEqualTo("2026-10-26T19:45:00Z") // Friday to Monday
        // Wednesday before Thanksgiving: Thursday is closed, Friday closes early at 13:00 New York.
        assertThat(expiry(stocks, "2025-11-26T21:00:00Z")).isEqualTo("2025-11-28T18:00:00Z")
    }

    @Test
    fun `FX expires at the 17_00 New York roll and crypto at midnight UTC`() {
        assertThat(expiry(TradingCalendar.fxDefault(), "2026-10-26T14:00:00Z")).isEqualTo("2026-10-26T21:00:00Z")
        assertThat(expiry(TradingCalendar.crypto(), "2026-10-26T14:00:00Z")).isEqualTo("2026-10-27T00:00:00Z")
    }

    @Test
    fun `only a DAY order with no expiry yet is stamped`() {
        val now = Instant.parse("2026-10-26T14:00:00Z").toEpochMilli()

        fun limit(
            tif: TimeInForce,
            expiresAt: Long? = null,
        ) = OrderRequest.Limit(
            id = "L",
            symbol = "AAPL",
            side = com.qkt.common.Side.BUY,
            quantity = java.math.BigDecimal.ONE,
            limitPrice = java.math.BigDecimal(300),
            timeInForce = tif,
            timestamp = now,
            expiresAt = expiresAt,
        )
        assertThat(limit(TimeInForce.DAY).withDayExpiry(stocks, now).expiresAt)
            .isEqualTo(Instant.parse("2026-10-26T19:45:00Z").toEpochMilli())
        assertThat(limit(TimeInForce.GTC).withDayExpiry(stocks, now).expiresAt).isNull()
        assertThat(limit(TimeInForce.DAY, 123L).withDayExpiry(stocks, now).expiresAt).isEqualTo(123L)
    }
}
