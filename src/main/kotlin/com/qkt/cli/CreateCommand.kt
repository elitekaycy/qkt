package com.qkt.cli

import java.nio.file.Path

/**
 * `qkt create template <path> [--kind <kind>]` — scaffold a new qkt project.
 *
 * Writes a working tree (compose stack, examples, Makefile, `.env.example`)
 * so a new operator can go from "I have qkt installed" to "I have a daemon running
 * on docker-compose" in two commands. Default kind is `mt5` — the canonical full
 * stack with `mt5-gateway`; `minimal` skips the gateway for paper-only / backtest
 * exploration; `bybit` swaps MT5 out for direct Bybit REST API (testnet by default);
 * `bot` layers the `qkt bot` AI-agent files (system prompt, bot guide) on the
 * mt5 stack.
 */
class CreateCommand(
    private val args: Args,
    private val scaffolder: TemplateScaffolder = TemplateScaffolder(),
) {
    fun run(): Int {
        val sub = args.firstNonOption()
        if (sub != "template") {
            System.err.println(
                "qkt: error: usage: qkt create template <path> [--kind ${VALID_KINDS.joinToString("|")}]",
            )
            return ExitCodes.USER_ERROR
        }
        val pathArg = args.positional(1)
        if (pathArg == null) {
            System.err.println("qkt: error: missing required argument: <path>")
            return ExitCodes.USER_ERROR
        }
        val kind = args.option("kind") ?: "mt5"
        if (kind !in VALID_KINDS) {
            System.err.println(
                "qkt: error: unknown --kind '$kind' (valid: ${VALID_KINDS.joinToString(", ")})",
            )
            return ExitCodes.USER_ERROR
        }
        val target = Path.of(pathArg).toAbsolutePath()
        val tokens = mapOf("QKT_VERSION" to BuildInfo.VERSION)
        val layers =
            when (kind) {
                "backtest" -> listOf("minimal", "backtest")
                "portfolio" -> listOf("minimal", "portfolio")
                "mt5-ci" -> listOf("mt5", "mt5-ci")
                "bot" -> listOf("mt5", "bot")
                else -> listOf(kind)
            }
        return when (val result = scaffolder.scaffoldLayers(layers, target, tokens)) {
            is TemplateScaffolder.Result.Created -> {
                println("Created ${result.filesWritten.size} files at $target")
                println("")
                println("Next steps:")
                println("  cd $target")
                println("  cp .env.example .env  # then edit .env with your broker credentials")
                if (kind in setOf("backtest", "portfolio")) {
                    println("  make backtest         # run the included research strategy")
                } else {
                    println("  make up               # start the qkt daemon")
                    println("  # copy a live-ready .qkt into strategies/, then:")
                    println("  make deploy STRAT=<name>")
                    println("  make logs")
                }
                ExitCodes.SUCCESS
            }

            is TemplateScaffolder.Result.Failed -> {
                System.err.println("qkt: error: ${result.reason}")
                ExitCodes.USER_ERROR
            }
        }
    }

    private companion object {
        private val VALID_KINDS =
            linkedSetOf("mt5", "mt5-ci", "backtest", "portfolio", "minimal", "bybit", "bot")
    }
}
