package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class ListCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body =
            try {
                client.list()
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            } catch (e: ControlClient.DaemonError) {
                System.err.println("qkt: error: list failed (${e.code}): ${e.body}")
                return ExitCodes.USER_ERROR
            }

        if (args.flag("json")) {
            println(body)
            return ExitCodes.SUCCESS
        }
        val arr = Json.parseToJsonElement(body) as? JsonArray
        if (arr == null) {
            println(body)
            return ExitCodes.SUCCESS
        }
        println("NAME          UPTIME   PORT     TRADES   STATE")
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?"
            val uptimeMs = o["uptimeMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val port = o["port"]?.jsonPrimitive?.contentOrNull ?: "?"
            val trades = o["trades"]?.jsonPrimitive?.contentOrNull ?: "0"
            val state = o["state"]?.jsonPrimitive?.contentOrNull ?: "?"
            println("%-13s %-8s %-8s %-8s %s".format(name, formatUptime(uptimeMs), port, trades, state))
        }
        return ExitCodes.SUCCESS
    }

    private fun formatUptime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
