package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `qkt stop <name> [--flatten]` — graceful shutdown of a deployed strategy via the control plane. `stopped` is said only
 * of a stop that left nothing held: one the daemon answers `not_flat` names what is held and why, and exits non-zero.
 */
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
        val answer = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val state = answer?.get("state") as? JsonPrimitive
        val notFlat = state?.contentOrNull == "not_flat"
        if (args.flag("json")) {
            println(body)
        } else if (notFlat) {
            val heldItems = answer?.get("held") as? JsonArray
            val held = heldItems.orEmpty().joinToString { it.jsonPrimitive.content }
            val detail = (answer?.get("detail") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val what = held.ifEmpty { "unknown" }
            System.err.println("qkt: error: $name is not flat, and it no longer trades. Held: $what")
            System.err.println("qkt: $detail")
        } else {
            println("[INFO] stopped $name")
        }
        return if (notFlat) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
    }
}
