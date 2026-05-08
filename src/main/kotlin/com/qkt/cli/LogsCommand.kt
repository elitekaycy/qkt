package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

class LogsCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.requirePositional(0, "<name>")
        val follow = args.flag("follow") || args.flag("f")
        val lines = args.option("lines")?.toIntOrNull() ?: 200
        val since = args.option("since")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val resp =
            try {
                client.logs(name, lines = lines, since = since, follow = follow)
            } catch (e: ControlClient.NoDaemonRunningException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        return resp.use { r ->
            if (!r.isSuccessful) {
                if (r.code == 404) {
                    System.err.println("qkt: error: unknown strategy: $name")
                } else {
                    System.err.println("qkt: error: logs failed (${r.code})")
                }
                return@use ExitCodes.USER_ERROR
            }
            val source = r.body?.source() ?: return@use ExitCodes.SUCCESS
            try {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    println(line)
                }
            } catch (_: java.io.IOException) {
                // disconnected
            }
            ExitCodes.SUCCESS
        }
    }
}
