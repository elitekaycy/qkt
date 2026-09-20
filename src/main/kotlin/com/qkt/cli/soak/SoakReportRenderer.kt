package com.qkt.cli.soak

import java.nio.file.Path
import java.time.Instant

/**
 * Renders the passing soak attestation JSON: the release identity, soak window, metrics, the
 * embedded parity evidence, and the file name and SHA-256 of each retained artifact.
 */
internal fun renderSoakReport(
    strategy: String,
    testingSha: String,
    image: String,
    startedAt: Instant,
    completedAt: Instant,
    tradingDays: Int,
    healthSamples: Long,
    parity: ParityEvidence,
    healthArtifact: Path,
    journalArtifact: Path,
    reconciliationArtifact: Path,
    coverageArtifact: Path,
    parityArtifact: Path,
    insightsArtifact: Path,
): String =
    buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"testingSha\": ").append(json(testingSha)).append(",\n")
        append("  \"image\": ").append(json(image)).append(",\n")
        append("  \"accountMode\": \"demo\",\n")
        append("  \"canaryStrategy\": ").append(json(strategy)).append(",\n")
        append("  \"startedAtUtc\": ").append(json(startedAt.toString())).append(",\n")
        append("  \"completedAtUtc\": ").append(json(completedAt.toString())).append(",\n")
        append("  \"tradingDays\": ").append(tradingDays).append(",\n")
        append("  \"status\": \"pass\",\n")
        append("  \"metrics\": {")
        append("\"unreconciledPositions\":0,")
        append("\"unknownOutcomePlacements\":0,")
        append("\"droppedTicks\":0,")
        append("\"healthSamples\":").append(healthSamples).append("},\n")
        append("  \"parity\": ").append(parity.root.toString()).append(",\n")
        append("  \"artifacts\": {")
        append("\"health\":").append(json(healthArtifact.fileName.toString())).append(',')
        append("\"journal\":").append(json(journalArtifact.fileName.toString())).append(',')
        append("\"reconciliation\":").append(json(reconciliationArtifact.fileName.toString())).append(',')
        append("\"coverage\":").append(json(coverageArtifact.fileName.toString())).append(',')
        append("\"parity\":").append(json(parityArtifact.fileName.toString())).append(',')
        append("\"insights\":").append(json(insightsArtifact.fileName.toString())).append("},\n")
        append("  \"artifactSha256\": {")
        append("\"health\":").append(json(sha256(healthArtifact))).append(',')
        append("\"journal\":").append(json(sha256(journalArtifact))).append(',')
        append("\"reconciliation\":").append(json(sha256(reconciliationArtifact))).append(',')
        append("\"coverage\":").append(json(sha256(coverageArtifact))).append(',')
        append("\"parity\":").append(json(sha256(parityArtifact))).append(',')
        append("\"insights\":").append(json(sha256(insightsArtifact))).append("}\n")
        append("}\n")
    }

private fun json(value: String): String =
    buildString {
        append('"')
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
