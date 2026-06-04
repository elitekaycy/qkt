package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.observe.GateStatus
import com.qkt.cli.observe.ObserveReportRenderer
import com.qkt.cli.observe.ObserveRunner
import com.qkt.cli.observe.PlacementSchedule
import java.time.Duration
import java.time.Instant

/**
 * `qkt observe --strategy <name> [--since 7d] [--control-port <p>] [--windows 6,7@55]` —
 * a read-only go/no-go report over the running daemon's control plane. Verifies the placement
 * window fired, no engine errors occurred, and reconciles PnL. See `docs/how-to/observe-strategy.md`.
 */
class ObserveCommand(
    private val args: Args,
) {
    fun run(): Int {
        val strategy =
            try {
                args.requireOption("strategy")
            } catch (e: ArgError) {
                System.err.println("qkt: ${e.message}")
                return ExitCodes.ARG_ERROR
            }
        val sinceArg = args.option("since") ?: "24h"
        val to = Instant.now()
        val from =
            try {
                to.minus(parseSince(sinceArg))
            } catch (e: ArgError) {
                System.err.println("qkt: ${e.message}")
                return ExitCodes.ARG_ERROR
            }
        val schedule = parseWindows(args.option("windows"))
        val client =
            ControlClient(
                stateDir = StateDir.resolve(args.option("state-dir")),
                explicitPort = args.option("control-port")?.toIntOrNull(),
            )
        return try {
            val statusJson = client.status(strategy)
            val logsText =
                client.logs(strategy, lines = 100_000, since = from.toString()).use { it.body?.string().orEmpty() }
            val gates = ObserveRunner.run(from, to, schedule, logsText, statusJson)
            print(ObserveReportRenderer.text(strategy, sinceArg, gates))
            if (gates.any { it.status == GateStatus.FAIL }) ExitCodes.USER_ERROR else ExitCodes.SUCCESS
        } catch (e: ControlClient.NoDaemonRunningException) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.USER_ERROR
        } catch (e: ControlClient.DaemonError) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.USER_ERROR
        }
    }

    private fun parseSince(s: String): Duration {
        val n = s.dropLast(1).toLongOrNull() ?: throw ArgError("bad --since '$s' (use 7d/24h/30m)")
        return when (s.last()) {
            'd' -> Duration.ofDays(n)
            'h' -> Duration.ofHours(n)
            'm' -> Duration.ofMinutes(n)
            else -> throw ArgError("bad --since '$s' (use 7d/24h/30m)")
        }
    }

    // Default = hedge-straddle's schedule; override with `--windows 6,7,12,13,14,15@55`.
    private fun parseWindows(spec: String?): PlacementSchedule {
        if (spec == null) return PlacementSchedule(setOf(6, 7, 12, 13, 14, 15), 55)
        val (hours, minute) = spec.split("@")
        return PlacementSchedule(hours.split(",").map { it.trim().toInt() }.toSet(), minute.toInt())
    }
}
