package com.qkt.cli

fun main(argv: Array<String>) {
    val args = Args(argv)
    val code =
        try {
            when (args.subcommand) {
                "parse" -> ParseCommand(args).run()
                "backtest" -> BacktestCommand(args).run()
                "run" -> RunCommand(args).run()
                "--version", "-v" -> {
                    println("qkt ${BuildInfo.VERSION}")
                    ExitCodes.SUCCESS
                }
                "--help", "help" -> {
                    printHelp()
                    ExitCodes.SUCCESS
                }
                else -> {
                    System.err.println("qkt: unknown subcommand '${args.subcommand}'")
                    ExitCodes.ARG_ERROR
                }
            }
        } catch (e: ArgError) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.ARG_ERROR
        }
    kotlin.system.exitProcess(code)
}

private fun printHelp() {
    println(
        """
        qkt — Kotlin trading-strategy DSL runtime

        USAGE
            qkt <subcommand> [arguments]

        SUBCOMMANDS
            parse <file>            parse and validate a .qkt file
            backtest <file> ...     run a one-shot backtest (Phase 12a Task 5)
            run <file> ...          run a strategy in foreground (Phase 12a Task 7)

        SEE ALSO
            qkt --version
            qkt help <subcommand>
        """.trimIndent(),
    )
}
