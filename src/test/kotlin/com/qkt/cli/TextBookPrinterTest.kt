package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BookAnalytics
import com.qkt.backtest.BookRiskEvent
import com.qkt.backtest.BookRiskReport
import com.qkt.backtest.CorrelationPair
import com.qkt.backtest.SampleCadence
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextBookPrinterTest : ReportPrinterFixture() {
    private fun result(
        bookRisk: BookRiskReport? = null,
        bookAnalytics: BookAnalytics? = null,
    ) = BacktestResult(
        emptyList(),
        emptyList(),
        emptyMap(),
        report("0"),
        emptyMap(),
        SampleCadence.CANDLE_CLOSE,
        bookAnalytics = bookAnalytics,
        bookRisk = bookRisk,
    )

    private fun printed(block: (PrintStream) -> Unit): List<String> {
        val buf = ByteArrayOutputStream()
        block(PrintStream(buf, true))
        return buf.toString().lines()
    }

    @Test
    fun `book risk prints vol exposure peaks and the event count`() {
        val risk =
            BookRiskReport(
                series = emptyList(),
                bookVol = BigDecimal("0.12"),
                maxGrossExposure = BigDecimal("25000"),
                maxNetExposure = BigDecimal("8000"),
                events = listOf(BookRiskEvent.DeRiskChange(1L, BigDecimal("0.5"))),
            )

        val lines = printed { TextBookPrinter.printBookRisk(result(bookRisk = risk), it) }

        assertThat(lines).containsSubsequence(
            "Book risk",
            "  book vol (annual):  0.12",
            "  max gross exposure: 25000",
            "  max net exposure:   8000",
            "  events:             1",
        )
    }

    @Test
    fun `book risk without a vol says n-a and omits a zero event count`() {
        val risk = BookRiskReport(emptyList(), null, BigDecimal.ONE, BigDecimal.ZERO)

        val lines = printed { TextBookPrinter.printBookRisk(result(bookRisk = risk), it) }

        assertThat(lines).contains("  book vol (annual):  n/a").noneMatch { it.contains("events:") }
    }

    @Test
    fun `book analytics lists contributions sorted by strategy and each correlation pair`() {
        val analytics =
            BookAnalytics(
                contributionToReturn = mapOf("b" to BigDecimal("0.4"), "a" to BigDecimal("0.6")),
                returnCorrelation = listOf(CorrelationPair("a", "b", BigDecimal("-0.2"))),
                riskContribution = mapOf("a" to BigDecimal("0.7"), "b" to BigDecimal("0.3")),
                drawdownContribution = emptyMap(),
            )

        val lines = printed { TextBookPrinter.printBookAnalytics(result(bookAnalytics = analytics), it) }

        assertThat(lines).containsSubsequence(
            "Book analytics",
            "  contribution to return:",
            "    ${"a".padEnd(20)} 0.6",
            "    ${"b".padEnd(20)} 0.4",
            "  risk contribution (PCTR):",
            "    ${"a".padEnd(20)} 0.7",
            "  return correlation:",
            "    a ~ b: -0.2",
        )
    }

    @Test
    fun `book analytics without correlations skips that block and single runs print nothing`() {
        val analytics =
            BookAnalytics(mapOf("a" to BigDecimal.ONE), emptyList(), mapOf("a" to BigDecimal.ONE), emptyMap())

        assertThat(printed { TextBookPrinter.printBookAnalytics(result(bookAnalytics = analytics), it) })
            .noneMatch { it.contains("return correlation") }
        assertThat(printed { TextBookPrinter.printBookAnalytics(result(), it) }).containsExactly("")
        assertThat(printed { TextBookPrinter.printBookRisk(result(), it) }).containsExactly("")
    }
}
