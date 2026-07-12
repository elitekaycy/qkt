package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `qkt kill [name] [--flatten]` — the operator kill switch (FIA §1.11): halts the
 * strategy (or all), which also cancels its venue-resting pendings, and with
 * `--flatten` closes every attributed venue position through the daemon control plane and
 * verifies broker truth before reporting success. The session stays alive; `qkt resume`
 * re-arms it after diagnosis.
 */
class KillCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.positional(0)
        val flatten = args.flag("flatten")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.kill(name, flatten)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                if (e.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: kill failed (${e.code}): ${e.body}")
                }
                return ExitCodes.USER_ERROR
            }
        val flattenVerified =
            !flatten ||
                runCatching {
                    Json
                        .parseToJsonElement(body)
                        .jsonObject["flattenVerified"]
                        ?.jsonPrimitive
                        ?.booleanOrNull == true
                }.getOrDefault(false)
        if (args.flag("json")) {
            println(body)
        } else {
            val scope = name ?: "all strategies"
            if (!flattenVerified) {
                System.err.println("qkt: error: killed $scope but flatten was not verified: $body")
                return ExitCodes.USER_ERROR
            }
            println("[INFO] killed $scope${if (flatten) " (flattened and verified)" else ""}")
        }
        return if (flattenVerified) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }
}
