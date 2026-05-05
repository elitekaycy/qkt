package com.qkt.indicators.range

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.SessionAnchor
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionExtremesTest {
    private val day15Start = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    private val source =
        object : MarketSource {
            override val name = "Fake"
            override val capabilities = setOf(MarketSourceCapability.BARS)

            override fun supports(symbol: String): Boolean = true

            override fun bars(
                symbol: String,
                window: TimeWindow,
                range: TimeRange,
            ): Sequence<Candle> =
                if (range.from.toEpochMilli() == day15Start) {
                    sequenceOf(
                        Candle(
                            "X",
                            Money.of("100"),
                            Money.of("125"),
                            Money.of("90"),
                            Money.of("110"),
                            Money.ZERO,
                            day15Start,
                            day15Start + 60_000L,
                        ),
                    )
                } else {
                    emptySequence()
                }
        }

    @Test
    fun `SessionHigh on current session returns max high`() {
        val clock = FixedClock(time = day15Start + 3_600_000L)
        val indicator = SessionHigh("X", SessionAnchor.CurrentSession, TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("120"), clock.time))
        assertThat(indicator.value() as BigDecimal).isEqualByComparingTo(Money.of("125"))
    }

    @Test
    fun `SessionLow on current session returns min low`() {
        val clock = FixedClock(time = day15Start + 3_600_000L)
        val indicator = SessionLow("X", SessionAnchor.CurrentSession, TradingCalendar.crypto(), source, clock)
        indicator.update(Tick("X", Money.of("100"), clock.time))
        assertThat(indicator.value() as BigDecimal).isEqualByComparingTo(Money.of("90"))
    }
}
