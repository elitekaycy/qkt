package com.qkt.marketdata.ingest

/**
 * An import that cannot proceed without guessing or without writing wrong data. [message] says what was
 * found and what to change (usually the flag that settles it); [lines] are offending input lines as
 * `file:line: text`, at most [MAX_LINES] of them. Nothing has been written when this is thrown.
 */
class ImportRefusal(
    message: String,
    val lines: List<String> = emptyList(),
) : RuntimeException(message) {
    /** The message followed by its offending lines, one per line. */
    fun describe(): String =
        buildString {
            append(message)
            for (line in lines.take(MAX_LINES)) append("\n  ").append(line)
            if (lines.size > MAX_LINES) append("\n  … and ${lines.size - MAX_LINES} more")
        }

    companion object {
        const val MAX_LINES = 20
    }
}

/** Where a value came from: the input's display name and 1-based line, plus the raw line for messages. */
data class InputLine(
    val file: String,
    val line: Long,
    val raw: String,
) {
    override fun toString(): String = "$file:$line: ${raw.take(RAW_PREVIEW)}"

    private companion object {
        const val RAW_PREVIEW = 160
    }
}
