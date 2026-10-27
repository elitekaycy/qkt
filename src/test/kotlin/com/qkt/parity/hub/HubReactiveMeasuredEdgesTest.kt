package com.qkt.parity.hub

import com.qkt.common.TradingCalendar
import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Edge cases the reactive strategies on the production store ran into (docs/research, 2026-10-27):
 * a record landing just before a weekend close, a holiday's empty record sharing an instant with a
 * late real one, and the freshness window's own boundary.
 */
class HubReactiveMeasuredEdgesTest {
    private val day = 86_400_000L

    private fun assertAllPaths(
        runs: HubScenarioHarness.Runs,
        expected: List<HubScenarioHarness.Fill>,
    ) {
        assertThat(runs.ticks).describedAs("tick backtest").containsExactlyElementsOf(expected)
        assertThat(runs.bars).describedAs("bar tier").containsExactlyElementsOf(expected)
        runs.live?.let { assertThat(it).describedAs("live").containsExactlyElementsOf(expected) }
    }

    private fun rates(
        tmp: Path,
        records: List<Rec>,
    ): Path = HubFixtureStore(tmp).dataset("test.rates", listOf(Field("chg")), records).write()

    private fun strategy(
        trigger: String,
        freshMs: Long,
        exit: String,
    ) = """
        STRATEGY edge VERSION 1
        SYMBOLS
            x = $trigger
            r = HUB:test.rates.USD EVERY 1d
        RULES
            WHEN NOW.epoch_ms - r.known_at <= $freshMs AND r.chg >= 0.10 AND POSITION.x = 0
            THEN SELL x SIZING 1 EXIT AFTER $exit
        """.trimIndent()

    @Test
    fun `a record half an hour before the weekend close is decided at Friday's last close and filled at the reopen`(
        @TempDir tmp: Path,
    ) {
        // EURUSD on hourly bars: the 21:00 bar closes at 22:00 Friday, but that close is only seen when
        // the next tick arrives, Sunday 22:00. NOW is the close, so the record (21:30:30) is 29.5 minutes
        // old: fresh within 4 hours and filled at the reopen, stale within 20 minutes and never traded.
        val known = Instant.parse("2024-01-12T21:30:30Z").toEpochMilli()
        val root = rates(tmp, listOf(Rec("USD", known, mapOf("chg" to "0.12"))))
        val from = Instant.parse("2024-01-12T18:00:00Z")
        val end = Instant.parse("2024-01-15T00:00:00Z")
        val shut = { t: Long ->
            t >= Instant.parse("2024-01-12T22:00:00Z").toEpochMilli() &&
                t < Instant.parse("2024-01-14T22:00:00Z").toEpochMilli()
        }
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:EURUSD" to "1.09"), from, end, closed = shut)

        fun run(freshMs: Long) =
            HubScenarioHarness.run(
                root,
                strategy("BACKTEST:EURUSD EVERY 1h", freshMs, "1h"),
                prices,
                from,
                end,
                calendar = TradingCalendar.fxDefault(),
            )

        fun fill(
            at: String,
            side: String,
        ) = HubScenarioHarness.Fill(Instant.parse(at), "BACKTEST:EURUSD", side)
        assertAllPaths(
            run(4 * 3_600_000L),
            listOf(fill("2024-01-14T22:00:00Z", "SELL"), fill("2024-01-14T23:00:00Z", "BUY")),
        )
        assertAllPaths(run(20 * 60_000L), emptyList())
    }

    private val t0 = Instant.parse("2024-03-05T00:00:00Z")

    private fun gold(
        minute: Long,
        side: String,
    ) = HubScenarioHarness.Fill(t0.plusSeconds(minute * 60), "BACKTEST:XAUUSD", side)

    private fun runGold(
        root: Path,
        freshMs: Long,
    ): HubScenarioHarness.Runs {
        val end = t0.plusSeconds(3600)
        val prices = HubScenarioHarness.tape(mapOf("BACKTEST:XAUUSD" to "2000"), t0, end)
        return HubScenarioHarness.run(root, strategy("BACKTEST:XAUUSD EVERY 1m", freshMs, "5m"), prices, t0, end)
    }

    @Test
    fun `a holiday's empty record published with a late real one does not hide the real change, in either order`(
        @TempDir tmp: Path,
    ) {
        // The day after a US bond holiday FRED publishes Friday's value and the holiday's empty row at
        // the same instant. The real change is the news; the empty row carries nothing to read.
        val known = t0.toEpochMilli() + 10 * 60_000L + 30_000L
        val real = Rec("USD", known, mapOf("chg" to "0.12"), key = "fri|USD", effectiveAt = known - 4 * day)
        val holiday = Rec("USD", known, mapOf("chg" to null), key = "mon|USD", effectiveAt = known - day)
        val expected = listOf(gold(11, "SELL"), gold(16, "BUY"))
        assertAllPaths(runGold(rates(tmp.resolve("a"), listOf(real, holiday)), 60_000L), expected)
        assertAllPaths(runGold(rates(tmp.resolve("b"), listOf(holiday, real)), 60_000L), expected)
        // Published a quarter of a minute later instead, the empty row is the newest record, its field
        // is unknown at the 11-minute close, and nothing trades.
        val later = holiday.copy(knownAt = known + 15_000L)
        assertAllPaths(runGold(rates(tmp.resolve("c"), listOf(real, later)), 60_000L), emptyList())
    }

    @Test
    fun `the freshness window includes its bound and nothing past it`(
        @TempDir tmp: Path,
    ) {
        // Known exactly on the 10-minute close: invisible at that close (published on its millisecond),
        // 60 000 ms old at the next one. `<= 60000` trades it there; `<= 59999` never does.
        val root = rates(tmp, listOf(Rec("USD", t0.toEpochMilli() + 10 * 60_000L, mapOf("chg" to "0.10"))))
        assertAllPaths(runGold(root, 60_000L), listOf(gold(11, "SELL"), gold(16, "BUY")))
        assertAllPaths(runGold(root, 59_999L), emptyList())
    }
}
