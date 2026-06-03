package com.qkt.cli.daemon

/**
 * Parses raw operator text into a [ControlCommand].
 *
 * Splits on whitespace; the first token (lowercased) is the command verb; the second token,
 * if present and non-blank, is the strategy name argument. Any further tokens are ignored.
 * Unrecognised or blank input produces [ControlCommand.Unknown] with the original untrimmed string.
 */
object CommandParser {
    fun parse(raw: String): ControlCommand {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ControlCommand.Unknown(raw)
        val verb = tokens[0].lowercase()
        val arg = tokens.getOrNull(1)?.takeIf { it.isNotBlank() }
        return when (verb) {
            "/status" -> ControlCommand.Status
            "/halt" -> ControlCommand.Halt(targetOf(arg))
            "/resume" -> ControlCommand.Resume(targetOf(arg))
            "/help" -> ControlCommand.Help
            else -> ControlCommand.Unknown(raw)
        }
    }

    private fun targetOf(arg: String?): Target = if (arg == null) Target.All else Target.Strategy(arg)
}
