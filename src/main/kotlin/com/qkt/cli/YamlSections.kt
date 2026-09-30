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
    ): String {
        val kept = StringBuilder()
        var keeping = false
        for (line in text.removeSuffix("\n").lineSequence()) {
            if (TOP_LEVEL_KEY.matches(line)) keeping = line.substringBefore(':') != key
            if (keeping) kept.append(line).append('\n')
        }
        return kept.toString()
    }
}
