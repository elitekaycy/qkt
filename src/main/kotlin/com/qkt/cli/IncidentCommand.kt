package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.cli.incident.IncidentBundle
import com.qkt.cli.incident.collectInput
import com.qkt.cli.incident.collectJournal
import com.qkt.cli.incident.collectLogs
import com.qkt.cli.incident.collectState
import com.qkt.cli.incident.renderIncidentManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipOutputStream

/** `qkt incident collect` builds a bounded evidence bundle for production triage. */
class IncidentCommand(
    private val args: Args,
    private val now: () -> Instant = { Instant.now() },
) {
    /** Execute the requested incident action and return a process exit code. */
    fun run(): Int =
        when (val action = args.positional(0)) {
            "collect" -> collect()
            else -> {
                System.err.println("qkt: unknown incident action '${action ?: ""}' (expected: collect)")
                System.err.println(
                    "usage: qkt incident collect [--state-dir <dir>] [--strategy <name>] " +
                        "[--since <instant|date>] [--until <instant|date>] [--out <zip>]",
                )
                ExitCodes.ARG_ERROR
            }
        }

    private fun collect(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val createdAt = now()
        val out =
            args
                .option("out")
                ?.let(Path::of)
                ?: Path.of("qkt-incident-${createdAt.toString().replace(":", "").replace("-", "")}.zip")
        val strategy = args.option("strategy")?.trim()?.takeIf { it.isNotEmpty() }
        val since = args.option("since")?.let { parseBoundary(it, endExclusive = false) }
        val until = args.option("until")?.let { parseBoundary(it, endExclusive = true) }
        if (since != null && until != null && since >= until) {
            System.err.println("qkt: --since must be earlier than --until")
            return ExitCodes.ARG_ERROR
        }
        val maxFileBytes = args.option("max-file-bytes")?.toLongOrNull() ?: DEFAULT_MAX_FILE_BYTES
        if (maxFileBytes <= 0L) {
            System.err.println("qkt: --max-file-bytes must be a positive integer")
            return ExitCodes.ARG_ERROR
        }

        val configPath = args.option("config")?.let(Path::of) ?: Config.locate()
        val strategyPath = args.option("strategy-file")?.let(Path::of)

        return try {
            out
                .toAbsolutePath()
                .normalize()
                .parent
                ?.let(Files::createDirectories)
            ZipOutputStream(
                Files.newOutputStream(
                    out,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ),
            ).use { zip ->
                val bundle = IncidentBundle(zip, maxFileBytes)
                collectJournal(bundle, stateDir, strategy, since, until)
                collectLogs(bundle, stateDir, strategy)
                collectState(bundle, stateDir, strategy)
                collectInput(bundle, "inputs/config.yaml", configPath)
                collectInput(bundle, "inputs/strategy.qkt", strategyPath)
                bundle.putText(
                    "manifest.json",
                    renderIncidentManifest(
                        createdAt = createdAt,
                        stateDir = stateDir.root.toAbsolutePath().normalize(),
                        out = out.toAbsolutePath().normalize(),
                        strategy = strategy,
                        since = since,
                        until = until,
                        maxFileBytes = maxFileBytes,
                        configPath = configPath,
                        strategyPath = strategyPath,
                        included = bundle.included,
                        warnings = bundle.warnings,
                    ),
                )
            }
            println("qkt incident collect: wrote ${out.toAbsolutePath().normalize()}")
            ExitCodes.SUCCESS
        } catch (e: Exception) {
            System.err.println("qkt: incident collect failed: ${e.message}")
            ExitCodes.USER_ERROR
        }
    }

    private fun parseBoundary(
        raw: String,
        endExclusive: Boolean,
    ): Long {
        raw.toLongOrNull()?.let { return it }
        runCatching { return Instant.parse(raw).toEpochMilli() }
        val day = LocalDate.parse(raw)
        val adjusted = if (endExclusive) day.plusDays(1) else day
        return adjusted.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L
    }
}
