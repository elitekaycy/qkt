package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleTest {
    private fun candle(
        bid: BigDecimal? = null,
        ask: BigDecimal? = null,
    ) = Candle(
        symbol = "XAUUSD",
        open = Money.of("2400"),
        high = Money.of("2402"),
        low = Money.of("2399"),
        close = Money.of("2401"),
        volume = Money.of("10"),
        startTime = 0L,
        endTime = 60_000L,
        bid = bid,
        ask = ask,
    )

    @Test
    fun `mid and spread are null when bid or ask absent`() {
        assertThat(candle().mid).isNull()
        assertThat(candle().spread).isNull()
        assertThat(candle(bid = Money.of("2400.5")).spread).isNull()
    }

    @Test
    fun `mid and spread compute when both bid and ask present`() {
        val c = candle(bid = Money.of("2400.5"), ask = Money.of("2401.5"))
        assertThat(c.mid).isEqualByComparingTo(Money.of("2401.0"))
        assertThat(c.spread).isEqualByComparingTo(Money.of("1.0"))
    }
}
