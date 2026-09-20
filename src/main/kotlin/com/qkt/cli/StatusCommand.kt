package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.status.renderDeepStatus
import com.qkt.cli.status.renderLatencyTable

/**
 * `qkt status [name]` — daemon-wide or per-strategy JSON status snapshot.
 *
 * `qkt status --deep` returns a single-screen human-readable health summary
 * aggregated across daemon + control plane + every deployed strategy. Exit code
 * is `0` when every subsystem is healthy, `1` if anything is wrong — designed
 * as the first-thing-to-run when an operator suspects something is off.
 */
class StatusCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    /** Print the requested status view and return a process exit code. */
    fun run(): Int {
        if (args.flag("latency")) return runLatency()
        if (args.flag("deep")) return runDeep()
        return runShallow()
    }

    private fun runShallow(): Int {
        val name = args.positional(0)
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.status(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: status failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        println(body)
        return ExitCodes.SUCCESS
    }

    private fun runDeep(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val healthBody =
            try {
                client.health()
            } catch (e: ControlClient.NoDaemonRunningException) {
                println("qkt: UNHEALTHY")
                System.err.println("DAEMON       not running (${e.message})")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                println("qkt: UNHEALTHY")
                System.err.println("DAEMON       /health failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        val listBody =
            try {
                client.list()
            } catch (e: ControlClient.NoDaemonRunningException) {
                println("qkt: UNHEALTHY")
                System.err.println("DAEMON       not running (${e.message})")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                println("qkt: UNHEALTHY")
                System.err.println("CONTROL      /list failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        return renderDeepStatus(healthBody, listBody)
    }

    /**
     * `qkt status --latency` — per-strategy per-stage latency percentiles. Hits the
     * daemon's `/latency` aggregator and renders a table. See #150.
     */
    private fun runLatency(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.latency()
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                System.err.println("qkt: error: latency fetch failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        return renderLatencyTable(body)
    }
}
