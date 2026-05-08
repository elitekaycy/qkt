package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

class StopCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.requirePositional(0, "<name>")
        val flatten = args.flag("flatten")
        val timeoutMs = args.option("timeout")?.toLongOrNull() ?: 5_000L
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.stop(name, flatten = flatten, timeoutMs = timeoutMs)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: stop failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        if (args.flag("json")) {
            println(body)
        } else {
            println("[INFO] stopped $name")
        }
        return ExitCodes.SUCCESS
    }
}
