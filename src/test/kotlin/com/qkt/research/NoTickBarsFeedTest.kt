package com.qkt.research

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.app.IntrabarFill
import com.qkt.backtest.NoTickBars
import com.qkt.common.Money
import com.qkt.evidence.BarsWithoutTicks
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** A tick-resolved bar the store has no ticks for: its synthetic path, its level marks, and its count (#1439, #1453). */
class NoTickBarsFeedTest {
    private fun bar(
        start: Long,
        o: String,
        h: String,
        l: String,
        c: String,
    ) = Candle("X", Money.of(o), Money.of(h), Money.of(l), Money.of(c), Money.of("0"), start, start + 1000)

    // Bar 0 has real ticks; bar 1 (from 1000) has none.
    private val slice = { _: String, from: Long, _: Long ->
        if (from == 0L) sequenceOf(Tick("X", Money.of("100"), 0), Tick("X", Money.of("101"), 500)) else emptySequence()
    }

    private val bars = sequenceOf(bar(0, "100", "102", "99", "101"), bar(1000, "101", "105", "98", "104"))

    /** Each emitted tick's price and whether a fill on it is at its level. */
    private fun drain(
        feed: BarResolvedFeed,
        noTickBars: NoTickBars,
    ): List<Pair<BigDecimal, Boolean>> =
        generateSequence { feed.next() }.map { it.price to noTickBars.fillsAtLevel("X") }.toList()

    @Test
    fun `a bar without ticks replays its synthetic path and only its extremes and close fill at their levels`() {
        val noTickBars = NoTickBars()
        val feed =
            BarResolvedFeed(mapOf("X" to bars), slice, { _, _, _, _ -> IntrabarFill.ALL_TICKS }, null, noTickBars)

        assertThat(drain(feed, noTickBars)).containsExactly(
            Money.of("100") to false,
            Money.of("101") to false,
            // The open fills at its print (a gap from the previous bar); the rest traded continuously.
            Money.of("101") to false,
            Money.of("98") to true,
            Money.of("105") to true,
            Money.of("104") to true,
        )
    }

    @Test
    fun `a short position sees a no-tick bar's high first, as bar fills order it`() {
        val noTickBars = NoTickBars(positionSign = { -1 })
        val feed =
            BarResolvedFeed(mapOf("X" to bars), slice, { _, _, _, _ -> IntrabarFill.ALL_TICKS }, null, noTickBars)

        assertThat(drain(feed, noTickBars).map { it.first }.drop(2))
            .containsExactly(Money.of("101"), Money.of("105"), Money.of("98"), Money.of("104"))
    }

    @Test
    fun `counts fill-possible bars without ticks once per symbol for the run summary`() {
        val noTickBars = NoTickBars()
        val many = (0 until 5).map { bar(it * 1000L, "100", "102", "99", "101") }.asSequence()
        val ticksOnFirst = { _: String, from: Long, _: Long ->
            if (from == 0L) sequenceOf(Tick("X", Money.of("100"), 0)) else emptySequence()
        }
        val feed =
            BarResolvedFeed(mapOf("X" to many), ticksOnFirst, { _, _, _, _ -> IntrabarFill.EXTREMES }, null, noTickBars)
        val logger = LoggerFactory.getLogger(NoTickBars::class.java) as Logger
        val logged = ListAppender<ILoggingEvent>().also { it.start() }
        val level = logger.level
        logger.level = Level.INFO
        logger.addAppender(logged)
        try {
            generateSequence { feed.next() }.count()
        } finally {
            logger.detachAppender(logged)
            logger.level = level
        }

        // One line for the symbol, not one per bar (#1453).
        assertThat(logged.list.map { it.formattedMessage }).containsExactly(
            "X has no ticks for a fill-possible bar at 1970-01-01T00:00:01Z; such bars fill at their levels (bar fills)",
        )

        val counts = noTickBars.counts()
        assertThat(
            counts,
        ).containsExactly(BarsWithoutTicks("X", withoutTicks = 4, fillPossible = 5, allWithoutTicks = 4))
        assertThat(counts.single().summary()).isEqualTo(
            "X: 4 of 5 fill-possible bars had no ticks; orders on them filled at their levels (bar fills), not from ticks",
        )
    }

    @Test
    fun `bars without ticks where no fill can land leave no summary`() {
        val noTickBars = NoTickBars()
        val feed =
            BarResolvedFeed(mapOf("X" to bars), slice, { _, _, _, _ -> IntrabarFill.SYNTHETIC }, null, noTickBars)
        generateSequence { feed.next() }.count()

        assertThat(noTickBars.counts()).isEmpty()
    }
}
