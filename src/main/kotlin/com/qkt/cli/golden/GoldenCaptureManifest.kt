package com.qkt.cli.golden

import com.qkt.cli.BuildInfo
import java.time.Instant

/** One bundle entry as the manifest records it: path, non-blank record count and SHA-256. */
internal data class EntryEvidence(
    val name: String,
    val records: Long,
    val sha256: String,
)

/** Renders the golden bundle's `manifest.json` (schema version 2, kind `MT5_GOLDEN_CAPTURE`). */
internal fun renderCaptureManifest(
    session: String,
    audit: AuditSummary,
    transport: TransportSummary,
    createdAt: Instant,
    entries: List<EntryEvidence>,
    readOnly: Boolean,
): String =
    buildString {
        append("{\n")
        append("  \"schemaVersion\": 2,\n")
        append("  \"kind\": \"MT5_GOLDEN_CAPTURE\",\n")
        append("  \"captureMode\": \"").append(if (readOnly) "READ_ONLY" else "TRADING").append("\",\n")
        append("  \"session\": ").append(jsonString(session)).append(",\n")
        append("  \"createdAtUtc\": ").append(jsonString(createdAt.toString())).append(",\n")
        append("  \"captureQktVersion\": ").append(jsonString(BuildInfo.VERSION)).append(",\n")
        append("  \"captureGitSha\": ").append(jsonString(BuildInfo.GIT_SHA)).append(",\n")
        append("  \"captureBuildTimestamp\": ").append(jsonString(BuildInfo.BUILD_TIMESTAMP)).append(",\n")
        append("  \"window\": {\"fromMs\": ").append(audit.firstTimestampMs)
        append(", \"toMs\": ").append(audit.lastTimestampMs).append("},\n")
        append("  \"counts\": {\"ticks\": ").append(audit.tickCount)
        append(", \"warmupTicks\": ").append(audit.warmupTickCount)
        append(", \"candles\": ").append(audit.candleCount)
        append(", \"streamCandles\": ").append(audit.streamCandleCount)
        append(", \"strategyCandleEvaluations\": ").append(audit.strategyCandleEvaluationCount)
        append(", \"fills\": ").append(audit.fillCount)
        append(", \"gatewayExchanges\": ").append(transport.exchangeCount)
        append(", \"linkedPlacements\": ").append(transport.linkedPlacements)
        append(", \"mutations\": ").append(transport.mutationCount).append("},\n")
        append("  \"entries\": [\n")
        entries.sortedBy { it.name }.forEachIndexed { index, evidence ->
            append("    {\"path\": ").append(jsonString(evidence.name))
            append(", \"records\": ").append(evidence.records)
            append(", \"sha256\": ").append(jsonString(evidence.sha256)).append('}')
            if (index != entries.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }
