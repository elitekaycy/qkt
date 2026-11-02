package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.TimeRange
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SymbolPattern
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A release calendar from a hub store, traded end to end through the backtest engine.
 *
 * The store holds what qkt-data-hub's schedule collector writes: one record per FOMC statement,
 * published one minute after the previous statement, with the statement instant in a `timestamp`
 * field `at`. A strategy that buys in the hour before each statement must enter on exactly the
 * bar an hour before every statement, in winter and summer alike, and on no other day -- and a
 * backtest that starts between two statements must still know the next one, because the record
 * announcing it was published before the window opened.
 */
class HubCalendarBacktestTest {
    private val ny = ZoneId.of("America/New_York")
    private val statements =
        listOf(
            "2024-01-31",
            "2024-03-20",
            "2024-05-01",
            "2024-06-12",
            "2024-07-31",
            "2024-09-18",
            "2024-11-07",
            "2024-12-18",
        ).map { ZonedDateTime.of(LocalDate.parse(it), LocalTime.of(14, 0), ny).toInstant().toEpochMilli() }

    /** The journal the hub's schedule backfill writes: each statement known one minute after the previous one. */
    private fun store(
        root: Path,
        events: List<Long> = statements,
    ): Path {
        val dir = root.resolve("journal").resolve("cal.us_fomc")
        dir.createDirectories()
        val first = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
        val lines =
            events.mapIndexed { i, at ->
                val knownAt = if (i == 0) first else events[i - 1] + 60_000L
                """{"v":1,"dataset":"cal.us_fomc","scope":"USD","key":"$at|USD","revision":1,"known_at":$knownAt,""" +
                    """"effective_at":$at,"availability":"derived","source":"us-schedule","seq":${i + 1},""" +
                    """"fields":{"at":$at,"kind":0}}"""
            }
        dir.resolve("2024-01-01.ndjson").writeText(lines.joinToString("\n", postfix = "\n"))
        return root
    }

    /** Five-minute EURUSD ticks across each statement day's 11:00-17:00 New York window. */
    private class Tape(
        statements: List<Long>,
    ) : MarketSource {
        private val ticks =
            statements.flatMap { at ->
                val start = at - 3 * 3_600_000L
                (0..72).map { i -> Tick("BACKTEST:EURUSD", Money.of("1.08"), start + i * 300_000L) }
            }

        override val name = "tape"
        override val capabilities = setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

        override fun supports(symbol: String) = symbol == "BACKTEST:EURUSD"

        /** Flat bars over the same ticks, so the `--bars` tier can replay the price stream too. */
        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> =
            ticks(symbol, range)
                .groupBy { it.timestamp / window.durationMs * window.durationMs }
                .map { (start, group) ->
                    val p = group.first().price
                    Candle(symbol, p, p, p, p, Money.ZERO, start, start + window.durationMs)
                }.asSequence()

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> =
            ticks.asSequence().filter {
                it.symbol == symbol &&
                    it.timestamp >= range.from.toEpochMilli() &&
                    it.timestamp < range.to.toEpochMilli()
            }
    }

    private val strategySource =
        """
        STRATEGY fomcHour VERSION 1

        SYMBOLS
            eu  = BACKTEST:EURUSD EVERY 5m
            cal = HUB:cal.us_fomc.USD EVERY 1d

        RULES
            WHEN cal.at - NOW.epoch_ms > 0 AND cal.at - NOW.epoch_ms <= 3600000 AND POSITION.eu = 0
            THEN BUY eu SIZING 1 EXIT AFTER 2h
        """.trimIndent()

    private fun entries(
        root: Path,
        from: String,
        events: List<Long> = statements,
        strategyText: String = strategySource,
        forceBars: Boolean = false,
    ): List<Long> {
        val source =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("HUB:") to HubMarketSource(root)),
                fallback = Tape(events),
            )
        val strategy = AstCompiler().compile((Dsl.parse(strategyText) as ParseResult.Success).value)
        val result =
            Backtest
                .fromSource(
                    strategies = listOf("s" to strategy),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BACKTEST:EURUSD", "HUB:cal.us_fomc.USD/at"),
                            from = Instant.parse(from),
                            to = Instant.parse("2025-01-01T00:00:00Z"),
                        ),
                    candleWindow = TimeWindow.parse("5m"),
                    startingBalance = BigDecimal("100000"),
                    forceBars = forceBars,
                    barWindows =
                        if (forceBars) {
                            mapOf(
                                "BACKTEST:EURUSD" to TimeWindow.parse("5m"),
                                "HUB:cal.us_fomc.USD/at" to TimeWindow.parse("1d"),
                            )
                        } else {
                            emptyMap()
                        },
                ).run()
        return result.trades.filter { it.trade.side == Side.BUY }.map { it.trade.timestamp }
    }

    @Test
    fun `every entry lands on the bar one hour before a statement, winter and summer`(
        @TempDir tmp: Path,
    ) {
        val got = entries(store(tmp), "2024-01-01T00:00:00Z")
        assertThat(got).containsExactlyElementsOf(statements.map { it - 3_600_000L })
        // 13:00 New York both under EST (January) and EDT (July): the zone, not a fixed offset.
        assertThat(
            got.map { Instant.ofEpochMilli(it).atZone(ny).toLocalTime() }.toSet(),
        ).containsExactly(LocalTime.of(13, 0))
    }

    @Test
    fun `a backtest starting between statements still knows the next one`(
        @TempDir tmp: Path,
    ) {
        // The March statement was announced at 2024-01-31 14:01 New York, before this window opens.
        val got = entries(store(tmp), "2024-02-15T00:00:00Z")
        assertThat(got).containsExactlyElementsOf(statements.drop(1).map { it - 3_600_000L })
    }

    @Test
    fun `facts closer together than the stream's window are all seen, in the bar tier as in ticks`(
        @TempDir tmp: Path,
    ) {
        // Six events 20 minutes apart on a stream declared EVERY 1d: the bar tier used to smear each
        // fact across a synthetic day bar and drop the next one as late, which live never does.
        val day = ZonedDateTime.of(LocalDate.parse("2024-03-20"), LocalTime.of(12, 0), ny).toInstant().toEpochMilli()
        val events = (1..6).map { day + it * 20 * 60_000L }
        val fast =
            strategySource
                .replace("<= 3600000", "<= 600000")
                .replace("EXIT AFTER 2h", "EXIT AFTER 5m")
        val root = store(tmp, events)
        val viaTicks = entries(root, "2024-03-20T00:00:00Z", events, fast)
        val viaBars = entries(root, "2024-03-20T00:00:00Z", events, fast, forceBars = true)
        assertThat(viaTicks).containsExactlyElementsOf(events.map { it - 600_000L })
        assertThat(viaBars).isEqualTo(viaTicks)
    }
}
