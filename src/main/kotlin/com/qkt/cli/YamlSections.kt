package com.qkt.cli

/**
 * Top-level sections of a YAML file, as text. Lets a command rewrite one section it owns while
 * keeping every other section byte for byte, comments and formatting included.
 */
internal object YamlSections {
    private val TOP_LEVEL_KEY = Regex("[A-Za-z_][A-Za-z0-9_-]*:.*")

    /** Every top-level section of [text] except [key], concatenated in file order. */
    fun except(
        text: String,
        key: String,
    ): String = sections(text) { it != key }

    /** The top-level section [key] of [text], its key line included; empty when [text] has none. */
    fun only(
        text: String,
        key: String,
    ): String = sections(text) { it == key }

    private fun sections(
        text: String,
        keep: (String) -> Boolean,
    ): String {
        val kept = StringBuilder()
        var keeping = false
        for (line in text.removeSuffix("\n").lineSequence()) {
            if (TOP_LEVEL_KEY.matches(line)) keeping = keep(line.substringBefore(':'))
            if (keeping) kept.append(line).append('\n')
        }
        return kept.toString()
    }
}
