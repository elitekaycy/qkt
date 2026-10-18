package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BarCompletenessValidatorTest {
    @Test
    fun `reports every missing trading day`(
        @TempDir root: Path,
    ) {
        val store = BinaryBarStore(root)
        val first = LocalDate.parse("2026-01-01")
        val start = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        store.writeDay(
            "BACKTEST",
            "BTCUSDT",
            TimeWindow.ONE_MINUTE,
            first,
            (0 until 1440).map { m ->
                val at = start + m * 60_000L
                Candle(
                    "BACKTEST:BTCUSDT",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    at,
                    at + 60_000L,
                )
            },
        )

        val coverage =
            BarCompletenessValidator.validate(
                store,
                "BACKTEST",
                "BTCUSDT",
                TimeWindow.ONE_MINUTE,
                first,
                first.plusDays(1),
                TradingCalendar.crypto(),
            )

        assertThat(coverage.requestedTradingDays).isEqualTo(2)
        assertThat(coverage.coveredTradingDays).isEqualTo(1)
        assertThat(coverage.missingDays).containsExactly(first.plusDays(1))
    }

    private val tf = TimeWindow.parse("15m")

    /** Writes 15m gold bars for [day] at every quarter hour in [hours] (UTC), header-only when [hours] is empty. */
    private fun goldDay(
        store: BinaryBarStore,
        day: LocalDate,
        hours: List<Int>,
    ) {
        val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val bars =
            hours.flatMap { h -> (0 until 4).map { q -> start + h * 3_600_000L + q * tf.durationMs } }.map { at ->
                Candle(
                    "BACKTEST:XAUUSD",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    at,
                    at + tf.durationMs,
                )
            }
        store.writeDay("BACKTEST", "XAUUSD", tf, day, bars)
    }

    /** Gold in winter: trading 23:00-22:00 UTC, the daily break 22:00-23:00. */
    private val winterHours = (0..21).toList() + 23

    private fun validate(
        store: BinaryBarStore,
        from: String,
        to: String,
        fetched: LocalBarStore? = null,
    ) = BarCompletenessValidator.validate(
        store,
        "BACKTEST",
        "XAUUSD",
        tf,
        LocalDate.parse(from),
        LocalDate.parse(to),
        TradingCalendar.fxDefault(),
        fetched,
    )

    @Test
    fun `a header-only bar file is a missing day, not a covered one`(
        @TempDir root: Path,
    ) {
        // #1441: January 2026's 15m files existed with 0 bars and the run said 26/26 trading days.
        val store = BinaryBarStore(root)
        val monday = LocalDate.parse("2026-01-05")
        goldDay(store, monday, winterHours)
        goldDay(store, monday.plusDays(1), emptyList())
        goldDay(store, monday.plusDays(2), winterHours)

        val coverage = validate(store, "2026-01-05", "2026-01-07")

        assertThat(coverage.requestedTradingDays).isEqualTo(3)
        assertThat(coverage.missingDays).containsExactly(monday.plusDays(1))
    }

    @Test
    fun `an empty fetched csv day is missing too, and a csv with bars covers an empty bin`(
        @TempDir root: Path,
    ) {
        val store = BinaryBarStore(root)
        val fetched = LocalBarStore(root)
        val monday = LocalDate.parse("2026-01-05")
        listOf(monday, monday.plusDays(2)).forEach { goldDay(store, it, winterHours) }
        fetched.writeDay("BACKTEST", "XAUUSD", "15m", monday.plusDays(1), emptyList())
        assertThat(validate(store, "2026-01-05", "2026-01-07", fetched).missingDays).containsExactly(monday.plusDays(1))

        goldDay(store, monday.plusDays(1), emptyList())
        val start =
            monday
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val bars =
            (0 until 96).map { i -> start + i * tf.durationMs }.filter { (it - start) / 3_600_000L != 22L }.map { at ->
                Candle(
                    "BACKTEST:XAUUSD",
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    at,
                    at + tf.durationMs,
                )
            }
        fetched.writeDay("BACKTEST", "XAUUSD", "15m", monday.plusDays(1), bars)
        assertThat(validate(store, "2026-01-05", "2026-01-07", fetched).missingDays).isEmpty()
    }

    @Test
    fun `holidays need no bars, a holiday short session passes and a plain weekday with none is missing`(
        @TempDir root: Path,
    ) {
        // #1438: 2023-11-24 (no file) must stay missing; 2023-12-24 (Sunday before Christmas) and 12-25 need nothing.
        val store = BinaryBarStore(root)
        listOf("2023-11-22", "2023-11-27", "2023-12-21", "2023-12-22", "2023-12-27").forEach {
            goldDay(store, LocalDate.parse(it), winterHours)
        }
        goldDay(store, LocalDate.parse("2023-11-23"), (0..17).toList() + 23) // Thanksgiving: gold halts early
        goldDay(store, LocalDate.parse("2023-11-26"), listOf(23))
        goldDay(store, LocalDate.parse("2023-12-26"), (1..21).toList() + 23) // reopens 01:00 after Christmas

        val november = validate(store, "2023-11-22", "2023-11-27")
        assertThat(november.missingDays).containsExactly(LocalDate.parse("2023-11-24"))
        assertThat(november.days.single { it.day == LocalDate.parse("2023-11-23") }.status)
            .isEqualTo(DayCompleteness.Status.SHORT)

        val christmas = validate(store, "2023-12-21", "2023-12-27")
        assertThat(christmas.missingDays).isEmpty()
        assertThat(christmas.days.filter { it.holiday }.map { it.day.toString() }).containsExactly("2023-12-24")
        assertThat(christmas.requestedTradingDays).isEqualTo(5) // 21, 22, 25 (from 22:00 UTC), 26, 27
    }
}
