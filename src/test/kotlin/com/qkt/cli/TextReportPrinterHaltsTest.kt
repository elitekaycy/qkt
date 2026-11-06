package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.SampleCadence
import com.qkt.events.RiskEvent
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextReportPrinterHaltsTest : ReportPrinterFixture() {
    @Test
    fun `the verbose report lists each risk halt with its strategy when known`() {
        val result =
            BacktestResult(
                emptyList(),
                emptyList(),
                emptyMap(),
                report("0"),
                emptyMap(),
                SampleCadence.CANDLE_CLOSE,
                halts =
                    listOf(
                        RiskEvent.Halted("max daily loss", "alpha", timestamp = 1_704_326_400_000L),
                        RiskEvent.Halted("engine fault", null, timestamp = 1_704_330_000_000L),
                    ),
            )
        val buf = ByteArrayOutputStream()

        TextReportPrinter.print(result, PrintStream(buf, true), BrokerKind.PAPER, verbose = true)

        assertThat(buf.toString().lines()).containsSubsequence(
            "Risk halts:       2",
            "  2024-01-04T00:00:00Z  max daily loss [alpha]",
            "  2024-01-04T01:00:00Z  engine fault",
        )
    }

    @Test
    fun `the default report is the concise view`() {
        val result =
            BacktestResult(emptyList(), emptyList(), emptyMap(), report("0"), emptyMap(), SampleCadence.CANDLE_CLOSE)
        val concise = ByteArrayOutputStream()
        val direct = ByteArrayOutputStream()

        TextReportPrinter.print(result, PrintStream(concise, true), BrokerKind.PAPER)
        ConciseReportPrinter.print(result, PrintStream(direct, true), BrokerKind.PAPER, emptySet(), emptySet())

        assertThat(concise.toString()).isEqualTo(direct.toString()).isNotBlank()
    }
}
