package com.qkt.cli

/**
 * The entries of an `instruments:` section as text, by `qktSymbol`, so a pull can rewrite the entries
 * it names and keep every other one byte for byte, comments included. An entry starts at a list item
 * (`  - …`) and runs to the next; lines before the first item (the file header) belong to none.
 */
internal class InstrumentEntryBlocks private constructor(
    private val blocks: Map<String, String>,
) {
    /** The text of [symbol]'s entry, or null when the section has none. */
    operator fun get(symbol: String): String? = blocks[symbol]

    /** Every entry's text, by symbol. */
    fun all(): Map<String, String> = blocks

    /** True when [symbol]'s entry sets [key] itself, outside a comment. */
    fun declares(
        symbol: String,
        key: String,
    ): Boolean {
        val text = blocks[symbol] ?: return false
        val pattern = Regex("(^|[\\s{,-])${Regex.escape(key)}\\s*:")
        return text.lineSequence().map { it.substringBefore('#') }.any { pattern.containsMatchIn(it) }
    }

    companion object {
        private val ITEM = Regex("^\\s*- .*")
        private val SYMBOL = Regex("qktSymbol\\s*:\\s*[\"']?([^\\s,\"'}]+)")

        /** The entries of the `instruments:` section of [yaml]; empty when it has none. */
        fun of(yaml: String): InstrumentEntryBlocks {
            val blocks = linkedMapOf<String, String>()
            val current = StringBuilder()

            fun flush() {
                SYMBOL.find(current)?.let { blocks[it.groupValues[1]] = current.toString() }
                current.clear()
            }
            var started = false
            for (line in YamlSections.only(yaml, "instruments").lineSequence().drop(1)) {
                if (ITEM.matches(line)) {
                    if (started) flush()
                    started = true
                }
                if (started && line.isNotEmpty()) current.append(line).append('\n')
            }
            if (started) flush()
            return InstrumentEntryBlocks(blocks)
        }
    }
}
