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
            "research" -> ResearchCommand(args).run()
            "run" -> RunCommand(args).run()
            "deploy" -> DeployCommand(args).run()
            "list" -> ListCommand(args).run()
            "stop" -> StopCommand(args).run()
            "start" -> StartCommand(args).run()
            "brokers" -> BrokersCommand(args).run()
            "editor" -> EditorCommand(args).run()
            "create" -> CreateCommand(args).run()
            "audit-ticks" -> AuditTicksCommand(args).run()
            "fetch" -> FetchCommand(args).run()
            "daemon" -> DaemonCommand(args).run()
            "logs" -> LogsCommand(args).run()
            "status" -> StatusCommand(args).run()
            "--version", "-v" -> {
                println(BuildInfo.versionLine())
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

        PROJECT SCAFFOLDING
            create template <path>  scaffold a new qkt project (--kind mt5|minimal|bybit)

        STRATEGY AUTHORING
            parse <file>            parse and validate a .qkt file
            backtest <file> ...     run a one-shot backtest against historical data
            research <file> ...     interactive playback REPL over historical data
            run <file> ...          run a strategy in foreground (paper-trading)

        DAEMON LIFECYCLE
            daemon start            start the long-lived daemon process
            daemon stop             stop the running daemon
            daemon status           show daemon health

        DAEMON OPERATIONS
            deploy <file> --as <n>  register and start a strategy in the daemon
            list                    list deployed strategies
            status [<name>]         show status of one strategy or all
            status --deep           aggregated health check (exit 1 if unhealthy)
            logs <name> [-f]        tail per-strategy log file
            stop <name> [--flatten] gracefully stop a deployed strategy
            start <portfolio>/<c>   clear operator-stop on a portfolio child

        VENUE / FEED
            brokers list            list configured broker profiles
            audit-ticks ...         capture and audit live MT5 ticks
            fetch BROKER:SYMBOL --tf <tf> --from <date> --to <date>
                                    backfill historical bars into the local store
            fetch BROKER:SYMBOL --tf <tf> --last 30d
                                    same, but for the last N days

        EDITOR INTEGRATIONS
            editor list             show supported editors + what's detected on this machine
            editor install <t>      install for vscode, nvim, vim, sublime, or all
            editor uninstall <t>    remove a previously-installed integration

        FLAGS
            --version, -v           print qkt version
            --help, help            this message

        DOCS
            https://elitekaycy.github.io/qkt/
        """.trimIndent(),
    )
}
