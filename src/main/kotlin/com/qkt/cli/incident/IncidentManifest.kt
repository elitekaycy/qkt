package com.qkt.cli.incident

import com.qkt.cli.BuildInfo
import com.qkt.evidence.EvidenceHasher
import com.qkt.evidence.EvidenceJson
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/** Renders the incident bundle's `manifest.json`: build, collection scope, input hashes, entries and warnings. */
internal fun renderIncidentManifest(
    createdAt: Instant,
    stateDir: Path,
    out: Path,
    strategy: String?,
    since: Long?,
    until: Long?,
    maxFileBytes: Long,
    configPath: Path?,
    strategyPath: Path?,
    included: List<String>,
    warnings: List<String>,
): String {
    val configAbsolute =
        configPath
            ?.toAbsolutePath()
            ?.normalize()
            ?.toString()
    val strategyAbsolute =
        strategyPath
            ?.toAbsolutePath()
            ?.normalize()
            ?.toString()
    return buildString {
        append("{\n")
        append("  \"createdAt\": ").append(json(createdAt.toString())).append(",\n")
        append("  \"qktVersion\": ").append(json(BuildInfo.VERSION)).append(",\n")
        append("  \"gitSha\": ").append(json(BuildInfo.GIT_SHA)).append(",\n")
        append("  \"buildTimestamp\": ").append(json(BuildInfo.BUILD_TIMESTAMP)).append(",\n")
        append("  \"stateDir\": ").append(json(stateDir.toString())).append(",\n")
        append("  \"output\": ").append(json(out.toString())).append(",\n")
        append("  \"strategy\": ").append(nullableJson(strategy)).append(",\n")
        append("  \"sinceTs\": ").append(since?.toString() ?: "null").append(",\n")
        append("  \"untilTsExclusive\": ").append(until?.toString() ?: "null").append(",\n")
        append("  \"maxFileBytes\": ").append(maxFileBytes).append(",\n")
        append("  \"configPath\": ").append(nullableJson(configAbsolute)).append(",\n")
        append("  \"configHash\": ").append(nullableJson(hashOrNull(configPath))).append(",\n")
        append("  \"strategyPath\": ").append(nullableJson(strategyAbsolute)).append(",\n")
        append("  \"strategyHash\": ").append(nullableJson(hashOrNull(strategyPath))).append(",\n")
        append("  \"included\": ").append(jsonList(included)).append(",\n")
        append("  \"warnings\": ").append(jsonList(warnings)).append("\n")
        append("}\n")
    }
}

private fun hashOrNull(path: Path?): String? =
    path
        ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        ?.let(EvidenceHasher::sha256)

private fun json(value: String): String = EvidenceJson.jsonString(value)

private fun nullableJson(value: String?): String = value?.let(::json) ?: "null"

private fun jsonList(values: List<String>): String = values.joinToString(",", prefix = "[", postfix = "]") { json(it) }
