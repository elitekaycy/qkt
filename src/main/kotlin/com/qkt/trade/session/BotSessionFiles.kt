package com.qkt.trade.session

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-disk descriptor of a running session, written to
 * `<stateRoot>/bot/sessions/<run>/session.json` so one-shot `qkt bot` verbs can
 * discover and route to it. Deleted on finish.
 */
data class BotSessionDescriptor(
    val runId: String,
    val port: Int,
    val token: String,
    val mode: String,
    val pid: Long,
) {
    fun toJson(): String = """{"runId":"$runId","port":$port,"token":"$token","mode":"$mode","pid":$pid}"""

    companion object {
        fun parse(text: String): BotSessionDescriptor {
            val obj = Json.parseToJsonElement(text).jsonObject

            fun str(key: String): String =
                obj[key]?.jsonPrimitive?.contentOrNull ?: error("session.json missing '$key'")
            return BotSessionDescriptor(
                runId = str("runId"),
                port = str("port").toInt(),
                token = str("token"),
                mode = str("mode"),
                pid = str("pid").toLong(),
            )
        }
    }
}

/** Session directory layout under one state root. */
object BotSessionFiles {
    fun sessionsRoot(stateRoot: Path): Path = stateRoot.resolve("bot").resolve("sessions")

    fun sessionDir(
        stateRoot: Path,
        runId: String,
    ): Path = sessionsRoot(stateRoot).resolve(runId)

    fun descriptorPath(
        stateRoot: Path,
        runId: String,
    ): Path = sessionDir(stateRoot, runId).resolve("session.json")

    /** Writes the descriptor atomically (temp + move) and returns its path. */
    fun write(
        stateRoot: Path,
        descriptor: BotSessionDescriptor,
    ): Path {
        val dir = sessionDir(stateRoot, descriptor.runId)
        Files.createDirectories(dir)
        val target = dir.resolve("session.json")
        val tmp = dir.resolve("session.json.tmp")
        Files.writeString(tmp, descriptor.toJson())
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /** Reads a run's descriptor, or null when no session file exists. */
    fun read(
        stateRoot: Path,
        runId: String,
    ): BotSessionDescriptor? {
        val path = descriptorPath(stateRoot, runId)
        if (!Files.isRegularFile(path)) return null
        return runCatching { BotSessionDescriptor.parse(Files.readString(path)) }.getOrNull()
    }

    /**
     * Resolves the session for a verb: explicit [runId] first, else `QKT_BOT_RUN`,
     * else the single existing session under the root (ambiguity fails closed).
     */
    fun resolve(
        stateRoot: Path,
        runId: String?,
        env: (String) -> String? = System::getenv,
    ): BotSessionDescriptor? {
        val explicit = runId ?: env("QKT_BOT_RUN")
        if (explicit != null) return read(stateRoot, explicit)
        val root = sessionsRoot(stateRoot)
        if (!Files.isDirectory(root)) return null
        val candidates =
            Files.list(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it.resolve("session.json")) }
                    .map { it.fileName.toString() }
                    .toList()
            }
        return when (candidates.size) {
            0 -> null
            1 -> read(stateRoot, candidates[0])
            else ->
                error(
                    "multiple bot sessions running (${candidates.sorted().joinToString(", ")}) — " +
                        "pass --run <id> or set QKT_BOT_RUN",
                )
        }
    }

    /** Removes the descriptor so verbs stop routing to a finished session. */
    fun delete(
        stateRoot: Path,
        runId: String,
    ) {
        Files.deleteIfExists(descriptorPath(stateRoot, runId))
    }
}
