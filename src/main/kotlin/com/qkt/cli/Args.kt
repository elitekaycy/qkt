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
}

class ArgError(
    msg: String,
) : RuntimeException(msg)
