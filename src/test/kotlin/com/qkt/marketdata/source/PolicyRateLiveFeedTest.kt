package com.qkt.marketdata.source

import com.qkt.common.FixedClock
import com.qkt.marketdata.store.macro.MacroPoint
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PolicyRateLiveFeedTest {
    @Test
    fun `emits the current value at observation time and suppresses unchanged polls`(
        @TempDir tmp: Path,
    ) {
        val now = Instant.parse("2024-03-05T12:00:00Z").toEpochMilli()
        val store = MacroSeriesStore(tmp)
        val date = LocalDate.of(2024, 3, 5)
        var refreshes = 0
        val feed =
            PolicyRateLiveFeed(
                symbols = mapOf("MACRO:RBA_CASH_RATE" to PolicyRateSeries.RBA_CASH_RATE),
                store = store,
                clock = FixedClock(now),
                pollIntervalMs = 1L,
                refresh = { series, _, _ ->
                    refreshes++
                    store.write(series.id, listOf(MacroPoint(date, BigDecimal("4.35"), now - 1_000)))
                },
            )

        val first = feed.next()
        feed.close()

        assertThat(first?.price).isEqualByComparingTo("4.35")
        assertThat(first?.timestamp).isEqualTo(now)
        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun `fails closed when the official artifact is stale`(
        @TempDir tmp: Path,
    ) {
        val now = Instant.parse("2024-03-15T12:00:00Z").toEpochMilli()
        val store = MacroSeriesStore(tmp)
        val staleDate = LocalDate.of(2024, 3, 7)
        val feed =
            PolicyRateLiveFeed(
                symbols = mapOf("MACRO:RBNZ_OCR" to PolicyRateSeries.RBNZ_OCR),
                store = store,
                clock = FixedClock(now),
                pollIntervalMs = 1L,
                refresh = { series, _, _ ->
                    store.write(series.id, listOf(MacroPoint(staleDate, BigDecimal("5.50"), now - 1_000)))
                },
            )

        assertThatIllegalStateException()
            .isThrownBy(feed::next)
            .withMessageContaining("official policy-rate source is stale")
        feed.close()
    }
}
