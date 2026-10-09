package com.qkt.cli

fun main(argv: Array<String>) {
    kotlin.system.exitProcess(exitCodeOf(argv))
}

/**
 * [command]'s exit code ([runMain] by default), or [ExitCodes.USER_ERROR] with the failure printed to [err] when the command throws.
 * A command that fails must still end the process: threads it started before failing (a feed's sockets, an
 * HTTP client's dispatcher) are not daemons, and an exception escaping `main` would leave the JVM running.
 */
internal fun exitCodeOf(
    argv: Array<String>,
    err: java.io.PrintStream = System.err,
    command: (Array<String>) -> Int = ::runMain,
): Int =
    try {
        command(argv)
    } catch (e: Throwable) {
        err.println("qkt: error: ${e.message ?: e::class.java.name}")
        e.printStackTrace(err)
        ExitCodes.USER_ERROR
    }

internal fun runMain(argv: Array<String>): Int =
    try {
        // Before anything logs: logback reads the strategy log directory when it first initializes.
        com.qkt.cli.daemon.logging.StateDirLogging
            .bind(argv)
        val args = Args(argv)
        if ("--help" in argv.drop(1) &&
            args.subcommand != "bot" &&
            CliOptionSchemas.forSubcommand(args.subcommand) != null
        ) {
            println(CliHelp.forCommand(args.subcommand))
            ExitCodes.SUCCESS
        } else {
            CliOptionSchemas.forSubcommand(args.subcommand)?.let { schema ->
                args.validateOptions(
                    valueOptions = schema.values,
                    flags = schema.flags,
                    optionalValueOptions = schema.optionalValues,
                    shortAliases = schema.shortAliases,
                )
            }
        when (args.subcommand) {
            "parse" -> ParseCommand(args).run()
            "dsl" -> DslCommand(args).run()
            "lsp" -> LspCommand().run()
            "backtest" -> BacktestCommand(args).run()
            "sweep" -> SweepCommand(args).run()
            "walkforward" -> WalkForwardCommand(args).run()
            "experiment" -> ExperimentCommand(args).run()
            "research" -> ResearchCommand(args).run()
            "run" -> RunCommand(args).run()
            "deploy" -> DeployCommand(args).run()
            "resync" -> ResyncCommand(args).run()
            "list" -> ListCommand(args).run()
            "stop" -> StopCommand(args).run()
            "start" -> StartCommand(args).run()
            "halt" -> HaltCommand(args).run()
            "kill" -> KillCommand(args).run()
            "reconcile" -> ReconcileCommand(args).run()
            "resume" -> ResumeCommand(args).run()
            "brokers" -> BrokersCommand(args).run()
            "instruments" -> InstrumentsCommand(args).run()
            "editor" -> EditorCommand(args).run()
            "create" -> CreateCommand(args).run()
            "audit-ticks" -> AuditTicksCommand(args).run()
            "fetch" -> FetchCommand(args).run()
            "data" -> DataCommand(args).run()
            "preflight" -> PreflightCommand(args).run()
            "promotion" -> PromotionCommand(args).run()
            "incident" -> IncidentCommand(args).run()
            "golden" -> GoldenCommand(args).run()
            "soak" -> SoakCommand(args).run()
            "bot" ->
                com.qkt.cli.bot
                    .BotCommand(args)
                    .run()
            "daemon" -> DaemonCommand(args).run()
            "logs" -> LogsCommand(args).run()
            "status" -> StatusCommand(args).run()
            "observe" -> ObserveCommand(args).run()
            "--version", "-v" -> {
                println(BuildInfo.versionLine())
                ExitCodes.SUCCESS
            }
            "--help", "help" -> {
                printHelp()
                ExitCodes.SUCCESS
            }
            else -> {
                val hint = CliHelp.suggestCommand(args.subcommand)
                if (hint != null) {
                    System.err.println("qkt: unknown command '${args.subcommand}'. Did you mean 'qkt $hint'?")
                    System.err.println(CliHelp.forCommand(hint))
                } else {
                    System.err.println("qkt: unknown subcommand '${args.subcommand}'")
                    System.err.println(CliHelp.topLevelHelp)
                }
                ExitCodes.ARG_ERROR
            }
        }
        }
    } catch (e: ArgError) {
        System.err.println("qkt: error: ${e.message}")
        ExitCodes.ARG_ERROR
    }

private fun printHelp() {
    println(CliHelp.topLevelHelp)
}
