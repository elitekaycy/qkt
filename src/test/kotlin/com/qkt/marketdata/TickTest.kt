package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TickTest {
    @Test
    fun `mid and spread null when bid or ask absent`() {
        val tick = Tick("EURUSD", Money.of("1.0843"), 0L, volume = Money.of("1"))
        assertThat(tick.mid).isNull()
        assertThat(tick.spread).isNull()
    }

    @Test
    fun `mid and spread compute when both bid and ask present`() {
        val tick =
            Tick(
                symbol = "EURUSD",
                price = Money.of("1.0842"),
                timestamp = 0L,
                bid = Money.of("1.0841"),
                ask = Money.of("1.0843"),
            )
        assertThat(tick.mid).isEqualByComparingTo(Money.of("1.0842"))
        assertThat(tick.spread).isEqualByComparingTo(Money.of("0.0002"))
    }
}
