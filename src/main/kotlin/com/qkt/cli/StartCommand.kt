package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

/** `qkt start <portfolio>/<child>` — clears the operator-stop flag on a portfolio child. */
class StartCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.requirePositional(0, "<portfolio>/<child>")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.start(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                System.err.println("qkt: error: start failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }
        println(body)
        return ExitCodes.SUCCESS
    }
}
