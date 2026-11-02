package com.qkt.trade

import com.qkt.common.TradingCalendar
import com.qkt.execution.OrderRequest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A one-shot DAY order expires where a strategy's does: at the end of its symbol's session, from the calendar the
 * account trades it on. Without a venue calendar it keeps the UTC day it always had.
 */
class BotDayExpiryTest : BotActionCompilerFixture() {
    private fun dayLimitExpiry(calendar: TradingCalendar): Instant {
        val out = compile(intent(limit = "2600", tif = BotTif.DAY), ctx.copy(calendar = calendar))
        return Instant.ofEpochMilli(requireNotNull((out.request as OrderRequest.Limit).expiresAt))
    }

    @Test
    fun `on a stock calendar the order rests to the next session's close, past the New Year holiday`() {
        // Placed 1970-01-01 00:00:01 UTC, a Thursday the NYSE is closed: Friday's 15:45 New York close.
        val stocks = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse")
        assertThat(dayLimitExpiry(stocks)).isEqualTo(Instant.parse("1970-01-02T20:45:00Z"))
    }

    @Test
    fun `on FX it ends at the 17_00 New York roll, and with no venue calendar at midnight UTC`() {
        assertThat(dayLimitExpiry(TradingCalendar.fxDefault())).isEqualTo(Instant.parse("1970-01-01T22:00:00Z"))
        assertThat(dayLimitExpiry(TradingCalendar.crypto())).isEqualTo(Instant.parse("1970-01-02T00:00:00Z"))
    }
}
