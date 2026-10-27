package com.qkt.connector.mt5

import com.qkt.common.SymbolCalendars
import com.qkt.common.TradingCalendar
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Exness files its US stock CFDs under `Standard\Stocks\`, and the built-in profile gives every symbol there the
 * session Exness quotes them in. Checked against the venue's whole symbol list as captured on 2026-10-27
 * (`mt5/exness-symbols-2026-10-27.tsv`): every stock moves to the stock session, and every other symbol keeps
 * exactly the calendar it had before.
 */
class MT5ExnessStockCalendarTest {
    private data class Listed(
        val bare: String,
        val category: String?,
    )

    private val listed: List<Listed> =
        checkNotNull(javaClass.getResourceAsStream("/mt5/exness-symbols-2026-10-27.tsv"))
            .bufferedReader()
            .readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { line ->
                val (name, category) = line.split('\t')
                Listed(name.removeSuffix("m"), category.takeIf { it != "-" })
            }

    private val exness = MT5DefaultProfiles.exness.symbolCalendars
    private val withCategories = exness.withCategories { bare -> listed.firstOrNull { it.bare == bare }?.category }

    @Test
    fun `every Exness stock trades the 06_00-15_45 New York session and nothing else moves`() {
        val stocks = listed.filter { it.category == "Stocks" }
        assertThat(stocks).hasSize(102)
        for (s in stocks) {
            assertThat(withCategories.calendarFor(s.bare).name).describedAs(s.bare).isEqualTo("nyse[06:00-15:45]")
        }
        for (s in listed - stocks.toSet()) {
            assertThat(withCategories.calendarFor(s.bare)).describedAs(s.bare).isSameAs(exness.calendarFor(s.bare))
        }
        // Without a category lookup (a config-only read before an account opens) nothing changes either.
        assertThat(exness.calendarFor("AAPL")).isSameAs(exness.calendarFor("EURUSD"))
    }

    @Test
    fun `the stock session is closed outside 06_00-15_45 New York and on NYSE holidays`() {
        val aapl = withCategories.calendarFor("AAPL")
        assertThat(aapl.isInSession("AAPL", Instant.parse("2026-10-26T14:00:00Z"))).isTrue()
        assertThat(aapl.isInSession("AAPL", Instant.parse("2026-10-26T20:00:00Z"))).isFalse()
        assertThat(aapl.isInSession("AAPL", Instant.parse("2026-10-24T14:00:00Z"))).isFalse()
        assertThat(aapl.isInSession("AAPL", Instant.parse("2025-11-27T14:00:00Z"))).isFalse()
        // FX on the same account still trades through the US evening.
        assertThat(
            withCategories.inSession("EXNESS:EURUSD", Instant.parse("2026-10-26T20:00:00Z").toEpochMilli()),
        ).isTrue()
    }

    @Test
    fun `a ticker rule in config overrides the category rule for that stock only`() {
        val overridden =
            withCategories.overriddenBy(listOf(SymbolCalendars.Rule("AAPL", TradingCalendar.nyse())))
        assertThat(overridden.calendarFor("AAPL")).isSameAs(TradingCalendar.nyse())
        assertThat(overridden.calendarFor("MSFT").name).isEqualTo("nyse[06:00-15:45]")
        assertThat(overridden.ruleFor("MSFT")?.pattern).isEqualTo("category:Stocks")
    }

    @Test
    fun `the category lookup is not part of a profile's identity`() {
        assertThat(withCategories).isEqualTo(exness)
        assertThat(withCategories.hashCode()).isEqualTo(exness.hashCode())
    }

    private fun info(path: String?) =
        MT5SymbolInfo(
            ask = BigDecimal.ONE,
            bid = BigDecimal.ONE,
            digits = 2,
            point = BigDecimal("0.01"),
            tradeStopsLevel = 0,
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            contractSize = BigDecimal(100),
            path = path,
        )

    @Test
    fun `the venue's folder is the category, read once, and a failed read is retried only after a while`() {
        assertThat(info("Standard\\Stocks\\AAPLm").category).isEqualTo("Stocks")
        assertThat(info("Stocks/AAPLm").category).isEqualTo("Stocks")
        assertThat(info("AAPLm").category).isNull()
        assertThat(info(null).category).isNull()

        val asked = mutableListOf<String>()
        var answer: MT5SymbolInfo? = null
        var now = 0L
        val categories =
            MT5SymbolCategories(
                { symbol ->
                    asked += symbol
                    answer
                },
                MT5Symbol(SymbolPolicy(suffix = "m")),
                monotonicNanos = { now },
                retryAfterNanos = 1_000L,
            )
        assertThat(categories("AAPL")).isNull() // gateway down
        assertThat(categories("AAPL")).isNull() // not asked again yet
        answer = info("Standard\\Stocks\\AAPLm")
        now = 1_000L
        assertThat(categories("AAPL")).isEqualTo("Stocks")
        assertThat(categories("AAPL")).isEqualTo("Stocks")
        assertThat(asked).containsExactly("AAPLm", "AAPLm")
    }
}
