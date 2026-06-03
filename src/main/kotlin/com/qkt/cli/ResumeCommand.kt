package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

/** `qkt resume [name]` — re-enable new-order submission for one strategy, or all if no name. */
class ResumeCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.positional(0)
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.resume(name)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: resume failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        if (args.flag("json")) println(body) else println("[INFO] resumed ${name ?: "all strategies"}")
        return ExitCodes.SUCCESS
    }
}
