package com.qkt.research

import com.qkt.app.IntrabarFill
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarResolvedFeedTest {
    // Candle(symbol, open, high, low, close, volume, startTime, endTime)
    private fun bar(
        start: Long,
        o: String,
        h: String,
        l: String,
        c: String,
    ) = Candle("X", Money.of(o), Money.of(h), Money.of(l), Money.of(c), Money.of("0"), start, start + 1000)

    private fun barV(
        start: Long,
        o: String,
        h: String,
        l: String,
        c: String,
        v: String,
    ) = Candle("X", Money.of(o), Money.of(h), Money.of(l), Money.of(c), Money.of(v), start, start + 1000)

    private fun drain(f: BarResolvedFeed): List<java.math.BigDecimal> {
        val out = mutableListOf<java.math.BigDecimal>()
        while (true) {
            val t = f.next() ?: break
            out.add(t.price)
        }
        return out
    }

    private fun drainTicks(f: BarResolvedFeed): List<Tick> {
        val out = mutableListOf<Tick>()
        while (true) out.add(f.next() ?: break)
        return out
    }

    @Test
    fun `ALL_TICKS bar streams all real ticks`() {
        val real = sequenceOf(Tick("X", Money.of("100"), 0), Tick("X", Money.of("101"), 500))
        val f =
            BarResolvedFeed(
                perSymbolBars = mapOf("X" to sequenceOf(bar(0, "100", "102", "99", "101"))),
                sliceProvider = { _, _, _ -> real },
                intrabarFill = { _, _, _ -> IntrabarFill.ALL_TICKS },
            )
        assertThat(drain(f)).containsExactly(Money.of("100"), Money.of("101"))
    }

    @Test
    fun `SYNTHETIC bar streams the real open then synthetic low-high-close`() {
        val f =
            BarResolvedFeed(
                perSymbolBars = mapOf("X" to sequenceOf(bar(0, "100", "102", "99", "101"))),
                sliceProvider = { _, _, _ -> sequenceOf(Tick("X", Money.of("100"), 0)) }, // just the open
                intrabarFill = { _, _, _ -> IntrabarFill.SYNTHETIC },
            )
        assertThat(drain(f)).containsExactly(Money.of("100"), Money.of("99"), Money.of("102"), Money.of("101"))
    }

    @Test
    fun `EXTREMES bar streams the open, only new-extreme ticks, then the close`() {
        // rest after the open: a non-extreme (100.5) is dropped; new highs/lows and the close are kept.
        val real =
            sequenceOf(
                Tick("X", Money.of("100"), 0), // open -> seeds the running extremes
                Tick("X", Money.of("100.5"), 100), // new max
                Tick("X", Money.of("101"), 200), // new max
                Tick("X", Money.of("99"), 300), // new min
                Tick("X", Money.of("103"), 400), // new max
                Tick("X", Money.of("102"), 500), // not an extreme -> DROPPED
                Tick("X", Money.of("105"), 600), // new max
                Tick("X", Money.of("101"), 999), // close
            )
        val f =
            BarResolvedFeed(
                perSymbolBars = mapOf("X" to sequenceOf(bar(0, "100", "105", "99", "101"))),
                sliceProvider = { _, _, _ -> real },
                intrabarFill = { _, _, _ -> IntrabarFill.EXTREMES },
            )
        assertThat(drain(f)).containsExactly(
            Money.of("100"),
            Money.of("100.5"),
            Money.of("101"),
            Money.of("99"),
            Money.of("103"),
            Money.of("105"),
            Money.of("101"),
        )
    }

    @Test
    fun `EXTREMES bar uses the must-feed slicer when present and applies the close residual`() {
        val opening = Tick("X", Money.of("100"), 0, volume = Money.of("2"))
        // The slicer pre-selects the rest extremes + close (real volumes); the feed must emit the
        // opening + these, with the close carrying the bar's residual volume (not its own).
        val mustFeed =
            listOf(
                Tick("X", Money.of("104"), 300, volume = Money.of("5")),
                Tick("X", Money.of("101"), 999, volume = Money.of("9")),
            )
        val f =
            BarResolvedFeed(
                perSymbolBars = mapOf("X" to sequenceOf(barV(0, "100", "105", "99", "101", "20"))),
                sliceProvider = { _, _, _ -> sequenceOf(opening) },
                intrabarFill = { _, _, _ -> IntrabarFill.EXTREMES },
                mustFeedSlicer = { _, _, _ -> mustFeed },
            )
        val ticks = drainTicks(f)
        assertThat(ticks.map { it.price }).containsExactly(Money.of("100"), Money.of("104"), Money.of("101"))
        // residual = barVolume(20) - opening(2) - extreme(5) = 13
        assertThat(ticks.last().volume).isEqualTo(Money.of("13"))
    }

    @Test
    fun `EXTREMES bar falls back to the decoded slice when the must-feed slicer returns null`() {
        val real =
            sequenceOf(
                Tick("X", Money.of("100"), 0),
                Tick("X", Money.of("103"), 200), // new max
                Tick("X", Money.of("102"), 500), // not an extreme -> DROPPED
                Tick("X", Money.of("101"), 999), // close
            )
        val f =
            BarResolvedFeed(
                perSymbolBars = mapOf("X" to sequenceOf(bar(0, "100", "103", "99", "101"))),
                sliceProvider = { _, _, _ -> real },
                intrabarFill = { _, _, _ -> IntrabarFill.EXTREMES },
                mustFeedSlicer = { _, _, _ -> null },
            )
        assertThat(drain(f)).containsExactly(Money.of("100"), Money.of("103"), Money.of("101"))
    }

    @Test
    fun `resolver is queried per bar with that bar's range`() {
        val seen = mutableListOf<Pair<java.math.BigDecimal, java.math.BigDecimal>>()
        val f =
            BarResolvedFeed(
                perSymbolBars =
                    mapOf(
                        "X" to sequenceOf(bar(0, "100", "102", "99", "101"), bar(1000, "101", "105", "100", "104")),
                    ),
                sliceProvider = { _, _, _ -> sequenceOf(Tick("X", Money.of("1"), 0)) },
                intrabarFill = { _, low, high ->
                    seen.add(low to high)
                    IntrabarFill.SYNTHETIC
                },
            )
        drain(f)
        assertThat(seen).containsExactly(Money.of("99") to Money.of("102"), Money.of("100") to Money.of("105"))
    }
}
