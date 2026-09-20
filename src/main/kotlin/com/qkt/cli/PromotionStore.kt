package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.decodeFromString

/** The append-only `promotions.jsonl` ledger under [root]; the latest record per strategy wins. */
class PromotionStore(
    private val root: Path,
) {
    private val file: Path = root.resolve("promotions.jsonl")

    fun append(record: PromotionRecord): PromotionRecord {
        Files.createDirectories(root)
        Files.writeString(
            file,
            PromotionJson.encode(record) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return record
    }

    fun all(): List<PromotionRecord> {
        if (!Files.exists(file)) return emptyList()
        return Files
            .readAllLines(file)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { PromotionJson.format.decodeFromString<PromotionRecord>(it) }
            .toList()
    }

    fun latest(strategy: String): PromotionRecord? =
        all()
            .asSequence()
            .filter { it.strategy == strategy }
            .maxByOrNull { Instant.parse(it.updatedAt) }

    fun latest(
        strategy: String,
        strategyHash: String,
    ): PromotionRecord? =
        all()
            .asSequence()
            .filter { it.strategy == strategy && it.strategyHash == strategyHash }
            .maxByOrNull { Instant.parse(it.updatedAt) }

    companion object {
        fun defaultRoot(): Path = UserDirs().stateHome().resolve("state").resolve("promotion")
    }
}
