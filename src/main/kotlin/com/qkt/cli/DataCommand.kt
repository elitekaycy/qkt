package com.qkt.cli

import com.qkt.cli.data.dataBuildBars
import com.qkt.cli.data.dataConvert
import com.qkt.cli.data.dataSnapshot
import com.qkt.cli.data.dataVerify

/**
 * `qkt data verify <symbol> [--data-root <dir>]`
 *
 * Reads every cached day file for a tick-store symbol and reports its quality — tick count and
 * largest intra-day gap — flagging empty, corrupt, or gappy days. The backtester keys coverage on
 * file *presence*, so a truncated or sparse day is otherwise concatenated in silently; this surfaces
 * it for any stream (not just Dukascopy). Exits non-zero when any day is flagged.
 */
class DataCommand(
    private val args: Args,
) {
    /** Execute the requested data action and return a process exit code. */
    fun run(): Int =
        when (val action = args.positional(0)) {
            "verify" -> dataVerify(args)
            "snapshot" -> dataSnapshot(args)
            "convert" -> dataConvert(args)
            "build-bars" -> dataBuildBars(args)
            else -> {
                System.err.println(
                    "qkt: unknown data action '${action ?: ""}' (expected: verify, snapshot, convert, build-bars)",
                )
                System.err.println("usage: qkt data <verify|snapshot|convert|build-bars> <symbol> [--data-root <dir>]")
                ExitCodes.ARG_ERROR
            }
        }
}
