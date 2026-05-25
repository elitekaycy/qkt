package com.qkt.cli

import java.nio.file.Path

/**
 * `qkt create template <path> [--kind mt5|minimal]` — scaffold a new qkt project.
 *
 * Writes a working tree (compose stack, sample strategy, Makefile, `.env.example`)
 * so a new operator can go from "I have qkt installed" to "I have a daemon running
 * on docker-compose" in two commands. Default kind is `mt5` — the canonical full
 * stack with `mt5-gateway`; `minimal` skips the gateway for paper-only / backtest
 * exploration.
 */
class CreateCommand(
    private val args: Args,
    private val scaffolder: TemplateScaffolder = TemplateScaffolder(),
) {
    fun run(): Int {
        val sub = args.firstNonOption()
        if (sub != "template") {
            System.err.println(
                "qkt: error: usage: qkt create template <path> [--kind mt5|minimal]",
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
        return when (val result = scaffolder.scaffold(kind, target, tokens)) {
            is TemplateScaffolder.Result.Created -> {
                println("Created ${result.filesWritten.size} files at $target")
                println("")
                println("Next steps:")
                println("  cd $target")
                println("  cp .env.example .env  # then edit .env with your broker credentials")
                println("  make up               # start the qkt daemon")
                println("  make deploy STRAT=ema_cross")
                println("  make logs")
                ExitCodes.SUCCESS
            }

            is TemplateScaffolder.Result.Failed -> {
                System.err.println("qkt: error: ${result.reason}")
                ExitCodes.USER_ERROR
            }
        }
    }

    private companion object {
        private val VALID_KINDS = setOf("mt5", "minimal")
    }
}
