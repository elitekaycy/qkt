package com.qkt.parity.hub

import com.qkt.common.TradingCalendar
import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A hub fact published while the traded market is shut: nothing fills until the market opens. */
class HubReactiveMarketHoursTest {
    @Test
    fun `a fact arriving while the FX market is shut is acted on when it opens, and is stale by then`(
        @TempDir tmp: Path,
    ) {
        // Saturday 13 January 2024 12:00 UTC; FX reopens Sunday 22:00 UTC.
        val saturday = Instant.parse("2024-01-13T12:00:30Z").toEpochMilli()
        val root =
            HubFixtureStore(tmp)
                .dataset("test.macro", listOf(Field("value")), listOf(Rec("USD", saturday, mapOf("value" to "1"))))
                .write()
        val from = Instant.parse("2024-01-12T20:00:00Z")
        val end = Instant.parse("2024-01-15T00:00:00Z")
        val shut = { t: Long ->
            t >= Instant.parse("2024-01-12T22:00:00Z").toEpochMilli() &&
                t < Instant.parse("2024-01-14T22:00:00Z").toEpochMilli()
        }
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:EURUSD" to "1.09"), from, end, closed = shut)

        fun strategy(fresh: Boolean) =
            """
            STRATEGY weekend VERSION 1
            SYMBOLS
                eur = BACKTEST:EURUSD EVERY 1m
                m   = HUB:test.macro.USD EVERY 1d
            RULES
                WHEN ${if (fresh) "NOW.epoch_ms - m.known_at <= 300000 AND " else ""}m.value > 0 AND POSITION.eur = 0
                THEN SELL eur SIZING 1
            """.trimIndent()
        val level =
            HubScenarioHarness.run(
                root,
                strategy(fresh = false),
                prices,
                from,
                end,
                calendar = TradingCalendar.fxDefault(),
                withLive = false,
            )
        val open = Instant.parse("2024-01-14T22:00:00Z")
        assertThat(level.ticks.map { it.at }).allMatch { !it.isBefore(open) }.isNotEmpty
        assertThat(level.bars).isEqualTo(level.ticks)
        val fresh =
            HubScenarioHarness.run(
                root,
                strategy(fresh = true),
                prices,
                from,
                end,
                calendar = TradingCalendar.fxDefault(),
                withLive = false,
            )
        assertThat(fresh.ticks).isEmpty()
        assertThat(fresh.bars).isEmpty()
    }
}
