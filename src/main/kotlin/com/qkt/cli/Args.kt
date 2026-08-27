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
    /** Original argv tokens in order, used for provenance records. */
    val tokens: List<String> = argv.toList()

    /** First argv token. Defaults to `"help"` when argv is empty. */
    val subcommand: String = argv.getOrNull(0) ?: "help"

    // `--name=value` is the spelling the daemon's own error text and the reference docs use
    // (`--reconcile=ignore-mismatches`); split it so both forms parse identically.
    private var rest: List<String> =
        argv.drop(1).flatMap { token ->
            val eq = token.indexOf('=')
            if (token.startsWith("--") &&
                eq > 2
            ) {
                listOf(token.substring(0, eq), token.substring(eq + 1))
            } else {
                listOf(token)
            }
        }

    /**
     * Validates option tokens before a command performs work and expands supported short aliases.
     *
     * [valueOptions] consume the following token, [flags] do not, and [optionalValueOptions]
     * support both forms. Unknown long or single-dash options fail closed.
     */
    internal fun validateOptions(
        valueOptions: Set<String> = emptySet(),
        flags: Set<String> = emptySet(),
        optionalValueOptions: Set<String> = emptySet(),
        shortAliases: Map<String, String> = emptyMap(),
    ) {
        rest =
            rest.map { token ->
                if (token.startsWith("-") && !token.startsWith("--")) {
                    shortAliases[token] ?: token
                } else {
                    token
                }
            }

        var i = 0
        while (i < rest.size) {
            val token = rest[i]
            if (!token.startsWith("-") || token == "-") {
                i++
                continue
            }
            if (!token.startsWith("--")) {
                throw ArgError("unknown flag $token")
            }
            val name = token.removePrefix("--")
            when (name) {
                in flags -> i++
                in valueOptions -> {
                    if (i + 1 >= rest.size || rest[i + 1].startsWith("--")) {
                        throw ArgError("missing value for --$name")
                    }
                    i += 2
                }
                in optionalValueOptions -> {
                    i +=
                        if (i + 1 < rest.size && !rest[i + 1].startsWith("-")) {
                            2
                        } else {
                            1
                        }
                }
                else -> throw ArgError("unknown flag --$name")
            }
        }
    }

    /**
     * Returns the [idx]th positional token after the subcommand, or `null` if absent.
     *
     * Positionals are the tokens before the first `--` flag. Stopping there keeps a
     * flag's value from being read as a positional — the parser doesn't know which
     * flags carry values, so `positions --config ./qkt.config.yaml` must not treat
     * the path as positional 0. Convention across all subcommands: positionals first.
     */
    fun positional(idx: Int): String? = rest.takeWhile { !it.startsWith("--") }.getOrNull(idx)

    /** `true` iff `--[name]` is present. Use [option] for flags that carry a value. */
    fun flag(name: String): Boolean = "--$name" in rest

    /** Returns the value of `--[name] <value>`, or `null` if the flag is absent. */
    fun option(name: String): String? {
        val i = rest.indexOf("--$name")
        return if (i >= 0 && i + 1 < rest.size) rest[i + 1] else null
    }

    /** Returns the value of every `--[name] <value>` occurrence, in argv order. */
    fun options(name: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < rest.size) {
            if (rest[i] == "--$name" && i + 1 < rest.size) {
                out.add(rest[i + 1])
                i += 2
            } else {
                i += 1
            }
        }
        return out
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
