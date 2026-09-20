package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.cli.golden.GoldenReplayDataMaterializer
import com.qkt.cli.golden.assertNoDrops
import com.qkt.cli.golden.jsonlFiles
import com.qkt.cli.golden.jsonlFilesRecursive
import com.qkt.cli.golden.scanAudit
import com.qkt.cli.golden.scanTransport
import com.qkt.cli.golden.writeGoldenBundle
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.nio.file.Path
import java.time.Instant

/** Exports an authentic daemon session into a compressed, checksummed golden-evidence bundle. */
class GoldenCommand(
    private val args: Args,
    private val clock: Clock = SystemClock(),
) {
    /** Execute the requested golden-evidence action and return a process exit code. */
    fun run(): Int =
        when (val action = args.positional(0)) {
            "capture" -> capture()
            "materialize" -> materialize()
            else -> {
                System.err.println("qkt: unknown golden action '${action ?: ""}' (expected: capture or materialize)")
                printUsage()
                ExitCodes.ARG_ERROR
            }
        }

    private fun materialize(): Int =
        try {
            val bundle = Path.of(args.requireOption("bundle"))
            val output = Path.of(args.requireOption("out"))
            val summary = GoldenReplayDataMaterializer(bundle, output, clock).materialize()
            println(
                "qkt golden materialize: wrote ${output.toAbsolutePath().normalize()} " +
                    "(${summary.liveTicks} live ticks, ${summary.warmupTicks} warmup ticks, " +
                    "${summary.candles} candles)",
            )
            ExitCodes.SUCCESS
        } catch (error: Exception) {
            System.err.println("qkt: golden materialize failed: ${error.message}")
            ExitCodes.USER_ERROR
        }

    private fun capture(): Int {
        val session = args.requireOption("session").trim()
        val readOnly = args.flag("read-only")
        if (session.isEmpty()) throw ArgError("--session must not be blank")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val safeSession = sanitize(session)
        val auditDir = stateDir.stateRoot.resolve("audit-journal").resolve(safeSession)
        val auditFiles = jsonlFiles(auditDir)
        if (auditFiles.isEmpty()) {
            System.err.println("qkt: no engine audit journal found for session '$session'")
            return ExitCodes.USER_ERROR
        }

        return try {
            val audit = scanAudit(auditFiles)
            require(audit.tickCount > 0L) { "session has no captured inbound ticks" }
            if (readOnly) {
                require(audit.fillCount == 0L) { "read-only session contains ${audit.fillCount} fill event(s)" }
            } else {
                require(audit.fillCount > 0L) { "session has no captured fills" }
            }
            assertNoDrops(auditDir, "audit", audit.firstTimestampMs, audit.lastTimestampMs)
            val transportRoot = stateDir.stateRoot.resolve("mt5-transport-journal")
            val transportFiles = jsonlFilesRecursive(transportRoot)
            val transport = scanTransport(transportFiles, audit)
            require(transport.exchangeCount > 0L) { "session window has no captured MT5 gateway exchanges" }
            if (readOnly) {
                require(transport.mutationCount == 0L) {
                    "read-only session contains ${transport.mutationCount} mutating gateway exchange(s)"
                }
            } else {
                require(transport.linkedPlacements > 0L) {
                    "session has no MT5 order exchange linked to a filled audit order"
                }
            }
            assertNoDrops(transportRoot, "transport", audit.firstTimestampMs, audit.lastTimestampMs)

            val createdAt = Instant.ofEpochMilli(clock.now())
            val output =
                args.option("out")?.let(Path::of)
                    ?: Path.of("qkt-golden-$safeSession-${createdAt.toString().replace(Regex("[-:]"), "")}.zip")
            writeGoldenBundle(
                output = output,
                stateDir = stateDir,
                session = session,
                safeSession = safeSession,
                auditFiles = auditFiles,
                audit = audit,
                transportFiles = transportFiles,
                transport = transport,
                createdAt = createdAt,
                readOnly = readOnly,
            )
            println("qkt golden capture: wrote ${output.toAbsolutePath().normalize()}")
            ExitCodes.SUCCESS
        } catch (error: Exception) {
            System.err.println("qkt: golden capture failed: ${error.message}")
            ExitCodes.USER_ERROR
        }
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun printUsage() {
        System.err.println(
            "usage: qkt golden capture --session <strategy> [--state-dir <dir>] [--out <zip>] [--read-only]",
        )
        System.err.println("       qkt golden materialize --bundle <zip> --out <data-root>")
    }
}
