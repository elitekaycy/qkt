package com.qkt.cli

/**
 * Tiny argv parser shared by every `qkt` subcommand.
 *
 * No external dependency by design — the CLI surface is small, parsing rules are
 * simple, and ad-hoc behavior is preferred over a heavyweight arg-parsing library.
 */
class Args(
    argv: Array<String>,
) {
    /** First argv token. Defaults to `"help"` when argv is empty. */
    val subcommand: String = argv.getOrNull(0) ?: "help"
    private val rest: List<String> = argv.drop(1)

    /** Returns the [idx]th non-flag token after the subcommand, or `null` if absent. */
    fun positional(idx: Int): String? = rest.filter { !it.startsWith("--") }.getOrNull(idx)

    /** `true` iff `--[name]` is present. Use [option] for flags that carry a value. */
    fun flag(name: String): Boolean = "--$name" in rest

    /** Returns the value of `--[name] <value>`, or `null` if the flag is absent. */
    fun option(name: String): String? {
        val i = rest.indexOf("--$name")
        return if (i >= 0 && i + 1 < rest.size) rest[i + 1] else null
    }

    /** Same as [option] but throws [ArgError] when missing. */
    fun requireOption(name: String): String = option(name) ?: throw ArgError("missing required flag --$name")

    /** Same as [positional] but throws [ArgError] when missing. [label] appears in the error message. */
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

/** Thrown by [Args.requireOption] / [Args.requirePositional] for missing required input. */
class ArgError(
    msg: String,
) : RuntimeException(msg)
