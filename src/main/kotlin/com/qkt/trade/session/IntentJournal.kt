package com.qkt.trade.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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

    @Synchronized
    fun fate(fate: IntentFate) = append("""{"type":"fate",${fateJson(fate).removePrefix("{")}""")

    private fun append(line: String) {
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun str(v: String): String =
        "\"" +
            v
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\""
}
