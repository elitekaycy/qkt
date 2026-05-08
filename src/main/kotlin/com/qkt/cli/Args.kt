package com.qkt.cli

class Args(
    argv: Array<String>,
) {
    val subcommand: String = argv.getOrNull(0) ?: "help"
    private val rest: List<String> = argv.drop(1)

    fun positional(idx: Int): String? = rest.filter { !it.startsWith("--") }.getOrNull(idx)

    fun flag(name: String): Boolean = "--$name" in rest

    fun option(name: String): String? {
        val i = rest.indexOf("--$name")
        return if (i >= 0 && i + 1 < rest.size) rest[i + 1] else null
    }

    fun requireOption(name: String): String = option(name) ?: throw ArgError("missing required flag --$name")

    fun requirePositional(
        idx: Int,
        label: String,
    ): String = positional(idx) ?: throw ArgError("missing required argument: $label")

    /**
     * Returns the first sub-subcommand token: a positional immediately following the main subcommand
     * that is not a flag (`--foo`) and not the value of a preceding `--foo` option.
     *
     * Used by commands like `qkt daemon stop` where the sub-subcommand must come before any options.
     */
    fun firstNonOption(): String? {
        val first = rest.firstOrNull() ?: return null
        if (first.startsWith("--")) return null
        return first
    }
}

class ArgError(
    msg: String,
) : RuntimeException(msg)
