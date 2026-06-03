package com.qkt.cli.daemon

/** Executes a [ControlCommand] against [DaemonControl] and returns a human-readable [CommandReply]. */
class CommandDispatcher(
    private val control: DaemonControl,
) {
    fun dispatch(command: ControlCommand): CommandReply =
        when (command) {
            is ControlCommand.Status -> CommandReply(formatStatus(control.status()))
            is ControlCommand.Halt -> CommandReply(formatResult("halted", control.halt(command.target)))
            is ControlCommand.Resume -> CommandReply(formatResult("resumed", control.resume(command.target)))
            is ControlCommand.Help -> CommandReply(USAGE)
            is ControlCommand.Unknown -> CommandReply("unknown command: ${command.raw}\n$USAGE")
        }

    private fun formatStatus(report: StatusReport): String {
        if (report.strategies.isEmpty()) return "no strategies deployed"
        val lines =
            report.strategies.joinToString("\n") { s ->
                val state = if (s.running) "running" else "stopped"
                val haltSuffix = if (s.halted) ", halted" else ""
                "- ${s.name}: $state$haltSuffix"
            }
        return "strategies (${report.strategies.size}):\n$lines"
    }

    private fun formatResult(
        verb: String,
        result: ControlResult,
    ): String =
        when {
            result.unknown.isNotEmpty() -> "unknown strategy: ${result.unknown.first()}"
            result.affected.isEmpty() -> "$verb: none"
            else -> "$verb: ${result.affected.joinToString(", ")}"
        }

    private companion object {
        private const val USAGE = "commands:\n/status\n/halt [name]\n/resume [name]\n/help"
    }
}
