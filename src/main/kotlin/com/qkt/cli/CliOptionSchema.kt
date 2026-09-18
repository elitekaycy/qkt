package com.qkt.cli

import com.qkt.cli.options.evidenceOptionSchemas
import com.qkt.cli.options.liveOptionSchemas
import com.qkt.cli.options.researchOptionSchemas
import com.qkt.cli.options.toolingOptionSchemas

/**
 * The options one subcommand accepts: [values] take an argument, [flags] do not, [optionalValues]
 * may take one, and [shortAliases] map a short spelling to its long flag.
 */
internal data class CliOptionSchema(
    val values: Set<String> = emptySet(),
    val flags: Set<String> = emptySet(),
    val optionalValues: Set<String> = emptySet(),
    val shortAliases: Map<String, String> = emptyMap(),
)

/** The option schema of every subcommand, used to reject unknown flags before dispatch. */
internal object CliOptionSchemas {
    private val schemas: Map<String, CliOptionSchema> =
        toolingOptionSchemas + researchOptionSchemas + liveOptionSchemas + evidenceOptionSchemas

    /** The schema for [subcommand], or null when the subcommand has none. */
    fun forSubcommand(subcommand: String): CliOptionSchema? = schemas[subcommand]
}
