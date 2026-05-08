package com.qkt.cli

fun main(argv: Array<String>) {
    val sub = argv.getOrNull(0) ?: "help"
    val code =
        when (sub) {
            "--version", "-v" -> {
                println("qkt ${BuildInfo.VERSION}")
                0
            }
            "--help", "help" -> {
                printHelp()
                0
            }
            else -> {
                printHelp()
                if (sub.startsWith("-")) 2 else 2
            }
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
            parse <file>            parse and validate a .qkt file (Phase 12a Task 3)
            backtest <file> ...     run a one-shot backtest (Phase 12a Task 5)
            run <file> ...          run a strategy in foreground (Phase 12a Task 7)

        SEE ALSO
            qkt --version
        """.trimIndent(),
    )
}
