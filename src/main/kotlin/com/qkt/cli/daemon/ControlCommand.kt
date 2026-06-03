package com.qkt.cli.daemon

/** A parsed operator command ready to be dispatched. */
sealed interface ControlCommand {
    data object Status : ControlCommand

    data class Halt(
        val target: Target,
    ) : ControlCommand

    data class Resume(
        val target: Target,
    ) : ControlCommand

    data object Help : ControlCommand

    /** An input that could not be matched to any known command. [raw] is the original untrimmed input. */
    data class Unknown(
        val raw: String,
    ) : ControlCommand
}
