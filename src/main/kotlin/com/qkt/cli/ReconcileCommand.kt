package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

/**
 * `qkt reconcile <name>` — engine-vs-broker truth comparison (FIA §2.1): per-symbol
 * net position deltas plus equity on both sides. Exit code is the alert: 0 when
 * clean, 2 on any delta — so a daily cron line IS the reconciliation control:
 *
 * ```cron
 * 0 6 * * * qkt reconcile mystrategy --json || page-telegram "qkt reconcile DELTA"
 * ```
 */
class ReconcileCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name =
            args.positional(0) ?: run {
                System.err.println("qkt: error: reconcile requires a strategy name")
                return ExitCodes.ARG_ERROR
            }
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val body =
            try {
                clientFactory(stateDir).reconcile(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                System.err.println("qkt: error: reconcile failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        println(body)
        return if ("\"clean\":true" in body) ExitCodes.SUCCESS else 2
    }
}
