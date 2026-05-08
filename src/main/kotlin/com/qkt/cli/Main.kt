package com.qkt.cli

fun main(argv: Array<String>) {
    kotlin.system.exitProcess(runMain(argv))
}

internal fun runMain(argv: Array<String>): Int {
    val args = Args(argv)
    return try {
        when (args.subcommand) {
            "parse" -> ParseCommand(args).run()
            "backtest" -> BacktestCommand(args).run()
            "run" -> RunCommand(args).run()
            "deploy" -> DeployCommand(args).run()
            "list" -> ListCommand(args).run()
            "stop" -> StopCommand(args).run()
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
}

private fun printHelp() {
    println(
        """
        qkt — Kotlin trading-strategy DSL runtime

        USAGE
            qkt <subcommand> [arguments]

        SUBCOMMANDS
            parse <file>            parse and validate a .qkt file
            backtest <file> ...     run a one-shot backtest
            run <file> ...          run a strategy in foreground (paper-trading)

        SEE ALSO
            qkt --version
            qkt help <subcommand>
        """.trimIndent(),
    )
}
