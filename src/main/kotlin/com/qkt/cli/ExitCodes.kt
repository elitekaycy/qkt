package com.qkt.cli

/** Exit-status convention used by every `qkt` subcommand. */
object ExitCodes {
    /** Normal exit. */
    const val SUCCESS = 0

    /** Reported user-side failure (e.g. broker rejection, file not found). */
    const val USER_ERROR = 1

    /** Invalid flags or arguments. */
    const val ARG_ERROR = 2
}
