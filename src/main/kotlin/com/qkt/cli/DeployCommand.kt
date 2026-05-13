package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** `qkt deploy <strategy.qkt> [--as <name>]` — register and start a strategy in a running daemon. */
class DeployCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file).toAbsolutePath()
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val name = args.option("as") ?: path.fileName.toString().removeSuffix(".qkt")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val ignoreMismatches = args.option("reconcile") == "ignore-mismatches"
        val body =
            try {
                client.deploy(name, path, ignoreMismatches)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                System.err.println("qkt: error: deploy failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }

        if (args.flag("json")) {
            println(body)
            return ExitCodes.SUCCESS
        }
        val obj = Json.parseToJsonElement(body) as? JsonObject
        if (obj == null) {
            println(body)
            return ExitCodes.SUCCESS
        }
        val n = obj["name"]?.jsonPrimitive?.contentOrNull ?: name
        val port = obj["port"]?.jsonPrimitive?.contentOrNull ?: "?"
        val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "?"
        val started = obj["startedAt"]?.jsonPrimitive?.contentOrNull ?: "?"
        println("NAME          PORT     STATE     STARTED")
        println("%-13s %-8s %-9s %s".format(n, port, state, started))
        return ExitCodes.SUCCESS
    }
}
