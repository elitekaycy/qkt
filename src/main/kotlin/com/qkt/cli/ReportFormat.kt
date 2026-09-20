package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import java.io.PrintStream

/** Output format selector for `qkt backtest` console reports. */
sealed interface ReportFormat {
    /** Aligned plaintext summary — the default. */
    data object Text : ReportFormat

    /** Single-line JSON — for piping into tooling. */
    data object Json : ReportFormat
}

/** Renders a [BacktestResult] in [ReportFormat.Text] or [ReportFormat.Json] form. */
object ReportPrinter {
    /**
     * Writes [result] in [fmt] form to [out]. [brokerKind] drives the execution-assumptions
     * disclosure — what the fills did and didn't model — so the report never reads as more
     * realistic than it is (#336).
     */
    fun print(
        result: BacktestResult,
        fmt: ReportFormat,
        out: PrintStream,
        brokerKind: BrokerKind,
    ) {
        when (fmt) {
            ReportFormat.Text -> TextReportPrinter.print(result, out, brokerKind)
            ReportFormat.Json -> JsonReportPrinter.print(result, out, brokerKind)
        }
    }
}
