package com.qkt.cli

import com.qkt.evidence.EvidenceJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class PersistedExperimentRecord(
    val id: String,
    val objectPath: Path,
    val indexPath: Path,
    val json: String,
)

class ExperimentRegistry(
    private val dir: Path,
) {
    fun write(recordBodyJson: String): PersistedExperimentRecord {
        require(recordBodyJson.startsWith("{")) { "recordBodyJson must be a JSON object" }
        val contentHash = sha256(recordBodyJson.toByteArray(StandardCharsets.UTF_8))
        val id = "sha256:$contentHash"
        val finalJson =
            "{\"schemaVersion\":1,\"id\":${EvidenceJson.jsonString(id)}," +
                "\"contentHash\":${EvidenceJson.jsonString(id)}," +
                recordBodyJson.removePrefix("{")

        val objects = dir.resolve("objects")
        Files.createDirectories(objects)
        val objectPath = objects.resolve("$contentHash.json")
        if (!Files.exists(objectPath)) {
            Files.writeString(objectPath, finalJson, StandardOpenOption.CREATE_NEW)
        }

        val indexPath = dir.resolve("experiments.jsonl")
        Files.createDirectories(indexPath.parent)
        Files.writeString(
            indexPath,
            finalJson + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return PersistedExperimentRecord(
            id = id,
            objectPath = objectPath,
            indexPath = indexPath,
            json = finalJson,
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
