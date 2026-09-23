package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a single [com.qkt.backtest.BacktestResult] to a directory as a bundle of
 * machine-readable artifacts (`result.json`, per-strategy equity curves, trades and
 * rejections CSVs) plus a rendered `report.html` summary.
 *
 * One writer per output directory; call [write] once per result. Strategy ids may contain
 * colon-separated `[A-Za-z0-9_-]+` segments. Colons are encoded when ids are embedded in filenames;
 * anything outside that grammar fails fast before the writer touches the filesystem.
 */
class BacktestReportWriter(
    private val dir: Path,
) {
    private val safeId = Regex("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*")

    /**
     * Emit every artifact for [result] into the writer's directory. Overwrites any
     * existing files; the directory itself must exist, be writable, and be free of
     * unsafe strategy ids before the call.
     */
    fun write(result: BacktestResult) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }
        for (id in result.perStrategy.keys) {
            require(safeId.matches(id)) { "Unsafe strategyId for filesystem write: $id" }
        }

        Files.writeString(dir.resolve("result.json"), ResultJson.render(result))
        Files.writeString(dir.resolve("equity_global.csv"), EquityCsv.render(result.global.equityCurve))
        for ((id, report) in result.perStrategy) {
            Files.writeString(dir.resolve(EquityCsv.fileName(id)), EquityCsv.render(report.equityCurve))
        }
        Files.writeString(dir.resolve("trades.csv"), TradesCsv.render(result.trades))
        Files.writeString(dir.resolve("financing.csv"), FinancingCsv.render(result.global.swapPaid))
        Files.writeString(dir.resolve("rejections.csv"), RejectionsCsv.render(result.rejections))
        Files.writeString(dir.resolve("orders.jsonl"), OrderDecisionsJsonl.render(result))
        Files.writeString(dir.resolve("pnl_components.csv"), PnlComponentsCsv.render(result))
        result.bookRisk?.let { Files.writeString(dir.resolve("book_risk.csv"), BookRiskCsv.render(it)) }
        result.global.monteCarlo?.let {
            Files.writeString(dir.resolve(MonteCarloFanCsv.FILE_NAME), MonteCarloFanCsv.render(it))
        }
        HtmlReportWriter().write(result, dir.resolve("report.html"))
        Files.writeString(dir.resolve("manifest.json"), ReportManifest.render(result, dir))
    }
}
