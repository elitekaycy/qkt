package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.evidence.EvidenceHasher
import com.qkt.evidence.EvidenceJson
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `qkt incident collect` builds a bounded evidence bundle for production triage. */
class IncidentCommand(
    private val args: Args,
    private val now: () -> Instant = { Instant.now() },
) {
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
        val included = mutableListOf<String>()
        val warnings = mutableListOf<String>()

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
                collectJournal(zip, stateDir, strategy, since, until, included, warnings)
                collectLogs(zip, stateDir, strategy, maxFileBytes, included, warnings)
                collectState(zip, stateDir, strategy, maxFileBytes, included, warnings)
                collectInput(zip, "inputs/config.yaml", configPath, maxFileBytes, included, warnings)
                collectInput(zip, "inputs/strategy.qkt", strategyPath, maxFileBytes, included, warnings)
                zip.putText(
                    "manifest.json",
                    renderManifest(
                        createdAt = createdAt,
                        stateDir = stateDir.root.toAbsolutePath().normalize(),
                        out = out.toAbsolutePath().normalize(),
                        strategy = strategy,
                        since = since,
                        until = until,
                        maxFileBytes = maxFileBytes,
                        configPath = configPath,
                        strategyPath = strategyPath,
                        included = included,
                        warnings = warnings,
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

    private fun collectJournal(
        zip: ZipOutputStream,
        stateDir: StateDir,
        strategy: String?,
        since: Long?,
        until: Long?,
        included: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val journalRoot = stateDir.stateRoot.resolve("journal")
        if (!Files.isDirectory(journalRoot)) {
            warnings.add("journal directory not found: $journalRoot")
            return
        }
        val strategyDirs =
            if (strategy != null) {
                listOf(journalRoot.resolve(strategy))
            } else {
                Files.list(journalRoot).use { stream ->
                    stream
                        .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                        .sorted()
                        .toList()
                }
            }
        for (strategyDir in strategyDirs) {
            if (!Files.isDirectory(strategyDir)) continue
            val strategyName = journalRoot.relativize(strategyDir).toString().replace('\\', '/')
            val files =
                Files.list(strategyDir).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .filter { it.fileName.toString().startsWith("journal-") }
                        .filter { it.fileName.toString().endsWith(".jsonl") }
                        .sorted()
                        .toList()
                }
            for (file in files) {
                val entryName = "journal-slice/${sanitizePath(strategyName)}/${file.fileName}"
                var opened = false
                Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!withinJournalWindow(line, since, until)) continue
                        if (!opened) {
                            zip.putNextEntry(ZipEntry(entryName))
                            opened = true
                        }
                        zip.write(line.toByteArray(StandardCharsets.UTF_8))
                        zip.write('\n'.code)
                    }
                }
                if (opened) {
                    zip.closeEntry()
                    included.add(entryName)
                }
            }
        }
    }

    private fun collectLogs(
        zip: ZipOutputStream,
        stateDir: StateDir,
        strategy: String?,
        maxFileBytes: Long,
        included: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val logsDir = stateDir.logsDir
        if (!Files.isDirectory(logsDir)) {
            warnings.add("logs directory not found: $logsDir")
            return
        }
        val files =
            if (strategy != null) {
                listOf(stateDir.logFile(strategy)).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            } else {
                regularFilesUnder(logsDir)
            }
        for (file in files) {
            val rel = logsDir.relativize(file)
            addFile(zip, file, "logs/${zipRelative(rel)}", maxFileBytes, included, warnings)
        }
    }

    private fun collectState(
        zip: ZipOutputStream,
        stateDir: StateDir,
        strategy: String?,
        maxFileBytes: Long,
        included: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val stateRoot = stateDir.stateRoot
        if (!Files.isDirectory(stateRoot)) {
            warnings.add("state directory not found: $stateRoot")
            return
        }
        val strategyPrefix = strategy?.let { Path.of(it) }
        for (file in regularFilesUnder(stateRoot)) {
            val rel = stateRoot.relativize(file)
            if (rel.nameCount > 0 && rel.getName(0).toString() == "journal") continue
            if (strategyPrefix != null && !rel.startsWith(strategyPrefix)) continue
            addFile(zip, file, "state/${zipRelative(rel)}", maxFileBytes, included, warnings)
        }
    }

    private fun collectInput(
        zip: ZipOutputStream,
        entryName: String,
        path: Path?,
        maxFileBytes: Long,
        included: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        if (path == null) return
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            warnings.add("input not found: $path")
            return
        }
        addFile(zip, path, entryName, maxFileBytes, included, warnings)
    }

    private fun addFile(
        zip: ZipOutputStream,
        path: Path,
        entryName: String,
        maxFileBytes: Long,
        included: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val size = Files.size(path)
        if (size > maxFileBytes) {
            warnings.add("truncated $entryName from $size to last $maxFileBytes bytes")
        }
        zip.putNextEntry(ZipEntry(entryName))
        Files.newInputStream(path).use { input ->
            if (size > maxFileBytes) input.skipNBytes(size - maxFileBytes)
            copyLimited(input, zip, maxFileBytes)
        }
        zip.closeEntry()
        included.add(entryName)
    }

    private fun renderManifest(
        createdAt: Instant,
        stateDir: Path,
        out: Path,
        strategy: String?,
        since: Long?,
        until: Long?,
        maxFileBytes: Long,
        configPath: Path?,
        strategyPath: Path?,
        included: List<String>,
        warnings: List<String>,
    ): String {
        val configAbsolute =
            configPath
                ?.toAbsolutePath()
                ?.normalize()
                ?.toString()
        val strategyAbsolute =
            strategyPath
                ?.toAbsolutePath()
                ?.normalize()
                ?.toString()
        return buildString {
            append("{\n")
            append("  \"createdAt\": ").append(json(createdAt.toString())).append(",\n")
            append("  \"qktVersion\": ").append(json(BuildInfo.VERSION)).append(",\n")
            append("  \"gitSha\": ").append(json(BuildInfo.GIT_SHA)).append(",\n")
            append("  \"buildTimestamp\": ").append(json(BuildInfo.BUILD_TIMESTAMP)).append(",\n")
            append("  \"stateDir\": ").append(json(stateDir.toString())).append(",\n")
            append("  \"output\": ").append(json(out.toString())).append(",\n")
            append("  \"strategy\": ").append(nullableJson(strategy)).append(",\n")
            append("  \"sinceTs\": ").append(since?.toString() ?: "null").append(",\n")
            append("  \"untilTsExclusive\": ").append(until?.toString() ?: "null").append(",\n")
            append("  \"maxFileBytes\": ").append(maxFileBytes).append(",\n")
            append("  \"configPath\": ").append(nullableJson(configAbsolute)).append(",\n")
            append("  \"configHash\": ").append(nullableJson(hashOrNull(configPath))).append(",\n")
            append("  \"strategyPath\": ").append(nullableJson(strategyAbsolute)).append(",\n")
            append("  \"strategyHash\": ").append(nullableJson(hashOrNull(strategyPath))).append(",\n")
            append("  \"included\": ").append(jsonList(included)).append(",\n")
            append("  \"warnings\": ").append(jsonList(warnings)).append("\n")
            append("}\n")
        }
    }

    private fun regularFilesUnder(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .toList()
        }

    private fun withinJournalWindow(
        line: String,
        since: Long?,
        until: Long?,
    ): Boolean {
        val ts = journalTs(line) ?: return since == null && until == null
        if (since != null && ts < since) return false
        if (until != null && ts >= until) return false
        return true
    }

    private fun journalTs(line: String): Long? {
        val start = line.indexOf("\"ts\":")
        if (start < 0) return null
        var i = start + "\"ts\":".length
        while (i < line.length && line[i].isWhitespace()) i++
        val valueStart = i
        while (i < line.length && line[i].isDigit()) i++
        return line.substring(valueStart, i).toLongOrNull()
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

    private fun hashOrNull(path: Path?): String? =
        path
            ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let(EvidenceHasher::sha256)

    private fun copyLimited(
        input: InputStream,
        zip: ZipOutputStream,
        maxBytes: Long,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            zip.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun ZipOutputStream.putText(
        entryName: String,
        text: String,
    ) {
        putNextEntry(ZipEntry(entryName))
        write(text.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun sanitizePath(value: String): String =
        value
            .replace('\\', '/')
            .split('/')
            .joinToString("/") { segment ->
                segment.replace(Regex("[^A-Za-z0-9._=-]"), "_")
            }

    private fun zipRelative(path: Path): String = sanitizePath(path.toString().replace('\\', '/'))

    private fun json(value: String): String = EvidenceJson.jsonString(value)

    private fun nullableJson(value: String?): String = value?.let(::json) ?: "null"

    private fun jsonList(values: List<String>): String =
        values.joinToString(",", prefix = "[", postfix = "]") { json(it) }

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L
        const val DEFAULT_BUFFER_SIZE = 8192
    }
}
