package com.qkt.trade.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * `intents.jsonl`: one `intent` line per accepted intent (its full DSL source, identity, the session time
 * it arrived at and [tickSeq], the number of ticks the engine had processed by then, which is where a
 * replay re-injects it), and one `fate` line each time what became of it changes. Append-only.
 */
class IntentJournal(
    val path: Path,
) {
    init {
        path.parent?.let { Files.createDirectories(it) }
    }

    @Synchronized
    fun intent(
        intentId: String,
        identity: String,
        source: String,
        simMs: Long,
        wallMs: Long,
        tickSeq: Long,
    ) = append(
        """{"type":"intent","intentId":"$intentId","identity":${str(identity)},"simMs":$simMs,"wallMs":$wallMs,""" +
            """"tickSeq":$tickSeq,"source":${str(source)}}""",
    )

    /** The point the session was finished at: a replay runs to here and no further. */
    @Synchronized
    fun finish(
        simMs: Long,
        tickSeq: Long,
    ) = append("""{"type":"finish","simMs":$simMs,"tickSeq":$tickSeq}""")

    @Synchronized
    fun fate(fate: IntentFate) = append("""{"type":"fate",${fateJson(fate).removePrefix("{")}""")

    private fun append(line: String) {
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    companion object {
        /** Every intent in [path], in the order they arrived, with its last journaled fate; empty when absent. */
        fun read(path: Path): List<JournaledIntent> {
            if (!Files.exists(path)) return emptyList()
            val lines =
                Files
                    .readAllLines(
                        path,
                    ).filter { it.isNotBlank() }
                    .map { Json.parseToJsonElement(it).jsonObject }
            val fates =
                lines.filter { it.text("type") == "fate" }.associate { f ->
                    val id = f.text("intentId")!!
                    id to IntentFate(id, f.text("identity")!!, f.text("state")!!, reason = f.text("reason"))
                }
            return lines.filter { it.text("type") == "intent" }.map { i ->
                val id = i.text("intentId")!!
                JournaledIntent(
                    intentId = id,
                    identity = i.text("identity")!!,
                    source = i.text("source")!!,
                    simMs = i.text("simMs")!!.toLong(),
                    tickSeq = i.text("tickSeq")!!.toLong(),
                    lastFate = fates[id],
                )
            }
        }

        /** The `(simMs, tickSeq)` the session in [path] was finished at, or null when it never finished. */
        fun finishPoint(path: Path): Pair<Long, Long>? {
            if (!Files.exists(path)) return null
            return Files
                .readAllLines(path)
                .filter { it.contains("\"type\":\"finish\"") }
                .lastOrNull()
                ?.let { Json.parseToJsonElement(it).jsonObject }
                ?.let { it.text("simMs")!!.toLong() to it.text("tickSeq")!!.toLong() }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun str(v: String): String =
        "\"" +
            v
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\""
}

/** One intent as an earlier run journaled it, with the last fate it reached. */
data class JournaledIntent(
    val intentId: String,
    val identity: String,
    val source: String,
    val simMs: Long,
    val tickSeq: Long,
    val lastFate: IntentFate?,
)
