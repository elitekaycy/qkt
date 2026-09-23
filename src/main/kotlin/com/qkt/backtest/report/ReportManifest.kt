package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.evidence.EvidenceHasher
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `manifest.json` artifact (schema `qkt-report-bundle-v1`): build provenance plus the
 * SHA-256 and size of every other artifact in the bundle. Rendered last, from the files already
 * on disk in the report directory; the manifest does not hash itself.
 */
internal object ReportManifest {
    fun render(
        result: BacktestResult,
        dir: Path,
    ): String {
        val artifacts =
            buildList {
                add("result.json")
                add("equity_global.csv")
                addAll(
                    result.perStrategy.keys
                        .sorted()
                        .map { EquityCsv.fileName(it) },
                )
                add("trades.csv")
                add("financing.csv")
                add("rejections.csv")
                add("orders.jsonl")
                add("pnl_components.csv")
                if (result.bookRisk != null) add("book_risk.csv")
                if (result.global.monteCarlo != null) add(MonteCarloFanCsv.FILE_NAME)
                add("report.html")
            }
        return buildString {
            append("{\n")
            append("  \"schema\": \"qkt-report-bundle-v1\",\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"selfHashIncluded\": false,\n")
            append("  \"generatedAt\": ")
                .append(result.evidence?.buildTimestamp?.let(ReportSerializer::jsonString) ?: "null")
                .append(",\n")
            append("  \"qktVersion\": ")
                .append(result.evidence?.qktVersion?.let(ReportSerializer::jsonString) ?: "null")
                .append(",\n")
            append("  \"gitSha\": ")
                .append(result.evidence?.gitSha?.let(ReportSerializer::jsonString) ?: "null")
                .append(",\n")
            append("  \"artifacts\": [")
            if (artifacts.isNotEmpty()) {
                append('\n')
                for ((index, artifact) in artifacts.withIndex()) {
                    val path = dir.resolve(artifact)
                    append("    {\"path\": ")
                        .append(ReportSerializer.jsonString(artifact))
                        .append(", \"sha256\": ")
                        .append(ReportSerializer.jsonString(EvidenceHasher.sha256(path)))
                        .append(", \"bytes\": ")
                        .append(Files.size(path))
                        .append("}")
                    if (index != artifacts.size - 1) append(',')
                    append('\n')
                }
                append("  ]\n")
            } else {
                append("]\n")
            }
            append("}")
        }
    }
}
