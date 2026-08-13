package com.qkt.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Builds a fail-closed release soak attestation from retained runtime evidence. */
class SoakCommand(
    private val args: Args,
) {
    /** Execute the requested soak-evidence action and return a process exit code. */
    fun run(): Int =
        when (val action = args.positional(0)) {
            "report" -> report()
            else -> {
                System.err.println("qkt: unknown soak action '${action ?: ""}' (expected: report)")
                printUsage()
                ExitCodes.ARG_ERROR
            }
        }

    private fun report(): Int {
        val strategy = args.requirePositional(1, "<strategy>")
        return try {
            val testingSha = required("testing-sha")
            require(testingSha.matches(Regex("[0-9a-f]{40}"))) {
                "--testing-sha must be a lowercase 40-character git SHA"
            }
            val image = required("image")
            require(image.matches(Regex("[^@]+@sha256:[0-9a-f]{64}"))) {
                "--image must be an immutable repository@sha256 digest"
            }
            val startedAt = Instant.parse(required("started-at"))
            val completedAt = Instant.parse(required("completed-at"))
            require(!completedAt.isBefore(startedAt)) { "--completed-at must not precede --started-at" }
            val tradingDays =
                required("trading-days").toIntOrNull()
                    ?: throw IllegalArgumentException("--trading-days must be an integer")
            require(tradingDays >= 0) { "--trading-days must be non-negative" }

            val healthSource = Path.of(required("health"))
            val reconciliationSource = Path.of(required("reconciliation"))
            val goldenSource = Path.of(required("golden"))
            val coverageSource = Path.of(required("coverage"))
            val paritySource = Path.of(required("parity"))
            val insightsSource = Path.of(required("insights"))
            val output = Path.of(required("out")).toAbsolutePath().normalize()
            require(Files.isRegularFile(healthSource)) { "health evidence not found: $healthSource" }
            require(Files.isRegularFile(reconciliationSource)) {
                "reconciliation evidence not found: $reconciliationSource"
            }
            require(Files.isRegularFile(goldenSource)) { "golden evidence not found: $goldenSource" }
            require(Files.isRegularFile(coverageSource)) { "coverage evidence not found: $coverageSource" }
            require(Files.isRegularFile(paritySource)) { "parity evidence not found: $paritySource" }
            require(Files.isRegularFile(insightsSource)) { "Insights evidence not found: $insightsSource" }

            val health = inspectHealth(healthSource, strategy)
            require(health.droppedTicks == 0L) {
                "health evidence reports ${health.droppedTicks} dropped tick(s)"
            }
            inspectReconciliation(reconciliationSource)
            val golden = inspectGolden(goldenSource, strategy)
            require(golden.unknownOutcomePlacements == 0L) {
                "golden evidence reports ${golden.unknownOutcomePlacements} unknown-outcome placement(s)"
            }
            val parity = inspectParity(paritySource)
            inspectJsonArtifact(coverageSource, "coverage")
            inspectJsonArtifact(insightsSource, "Insights")

            output.parent?.let(Files::createDirectories)
            val healthArtifact = output.resolveSibling("paper-soak-health.jsonl")
            val journalArtifact = output.resolveSibling("paper-soak-golden.zip")
            val reconciliationArtifact = output.resolveSibling("paper-soak-reconciliation.json")
            val coverageArtifact = output.resolveSibling("paper-soak-coverage.json")
            val parityArtifact = output.resolveSibling("paper-soak-parity.json")
            val insightsArtifact = output.resolveSibling("paper-soak-insights.json")
            copyArtifact(healthSource, healthArtifact)
            copyArtifact(goldenSource, journalArtifact)
            copyArtifact(reconciliationSource, reconciliationArtifact)
            copyArtifact(coverageSource, coverageArtifact)
            copyArtifact(paritySource, parityArtifact)
            copyArtifact(insightsSource, insightsArtifact)
            val report =
                render(
                    strategy = strategy,
                    testingSha = testingSha,
                    image = image,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    tradingDays = tradingDays,
                    healthSamples = health.samples,
                    parity = parity,
                    healthArtifact = healthArtifact,
                    journalArtifact = journalArtifact,
                    reconciliationArtifact = reconciliationArtifact,
                    coverageArtifact = coverageArtifact,
                    parityArtifact = parityArtifact,
                    insightsArtifact = insightsArtifact,
                )
            writeAtomic(output, report)
            println("qkt soak report: wrote $output")
            ExitCodes.SUCCESS
        } catch (error: Exception) {
            System.err.println("qkt: soak report failed: ${error.message}")
            ExitCodes.USER_ERROR
        }
    }

    private fun inspectHealth(
        path: Path,
        strategy: String,
    ): HealthEvidence {
        var samples = 0L
        var maxDroppedTicks = 0L
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            var lineNumber = 0L
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1L
                if (line.isBlank()) continue
                val root = parseObject(line, path, lineNumber)
                require(root["status"]?.jsonPrimitive?.contentOrNull == "ok") {
                    "unhealthy sample at $path:$lineNumber"
                }
                val row =
                    root["perStrategy"]
                        ?.jsonArray
                        ?.map { it.jsonObject }
                        ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == strategy }
                        ?: throw IllegalArgumentException("strategy '$strategy' missing at $path:$lineNumber")
                require(row["running"]?.jsonPrimitive?.booleanOrNull == true) {
                    "strategy '$strategy' was not running at $path:$lineNumber"
                }
                val dropped =
                    row["droppedTicks"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: throw IllegalArgumentException("missing droppedTicks at $path:$lineNumber")
                require(dropped >= 0L) { "negative droppedTicks at $path:$lineNumber" }
                maxDroppedTicks = maxOf(maxDroppedTicks, dropped)
                samples += 1L
            }
        }
        require(samples > 0L) { "health evidence contains no samples" }
        return HealthEvidence(samples, maxDroppedTicks)
    }

    private fun inspectReconciliation(path: Path) {
        val root = parseObject(Files.readString(path), path, 1L)
        require(root["clean"]?.jsonPrimitive?.booleanOrNull == true) {
            "final engine-to-venue reconciliation is not clean"
        }
    }

    private fun inspectJsonArtifact(path: Path, label: String) {
        parseObject(Files.readString(path), path, 1L)
        require(Files.size(path) > 0L) { "$label evidence is empty" }
    }

    private fun inspectParity(path: Path): ParityEvidence {
        val root = parseObject(Files.readString(path), path, 1L)
        fun positive(name: String): Long {
            val value = root[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: throw IllegalArgumentException("parity evidence missing $name")
            require(value > 0L) { "parity.$name must be positive" }
            return value
        }
        fun zero(name: String) {
            val value = root[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: throw IllegalArgumentException("parity evidence missing $name")
            require(value == 0L) { "parity.$name must be zero" }
        }
        val timeframes = root["timeframesTested"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { it.isNotBlank() }
            ?: throw IllegalArgumentException("parity evidence missing timeframesTested")
        require(timeframes.isNotEmpty()) { "parity.timeframesTested must be non-empty" }
        val duration = positive("durationMinutes")
        listOf("strategiesTested", "indicatorsTested", "mathScenariosTested", "dslScenariosTested",
            "orderTypesTested", "totalTicks", "totalBars", "fills", "parityComparisons",
            "insightsEvents", "warmupBars", "warmupTicks", "barBoundaryTransitions").forEach(::positive)
        listOf("parityMismatches", "unexplainedRejections", "unexplainedOrderOutcomes").forEach(::zero)
        return ParityEvidence(duration, timeframes, root)
    }

    private fun inspectGolden(
        path: Path,
        strategy: String,
    ): GoldenEvidence {
        var unknownOutcomes = 0L
        ZipFile(path.toFile()).use { zip ->
            val manifestEntry =
                zip.getEntry("manifest.json")
                    ?: throw IllegalArgumentException("golden bundle has no manifest.json")
            val manifest =
                zip.getInputStream(manifestEntry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    parseObject(reader.readText(), path, 1L)
                }
            require(manifest["kind"]?.jsonPrimitive?.contentOrNull == "MT5_GOLDEN_CAPTURE") {
                "unsupported golden bundle kind"
            }
            require(manifest["session"]?.jsonPrimitive?.contentOrNull == strategy) {
                "golden bundle session does not match '$strategy'"
            }
            val counts =
                manifest["counts"]?.jsonObject
                    ?: throw IllegalArgumentException("golden bundle has no counts")
            for (field in listOf("ticks", "fills", "gatewayExchanges", "linkedPlacements")) {
                val count = counts[field]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                require(count > 0L) { "golden bundle has no $field" }
            }
            val evidenceEntries =
                manifest["entries"]?.jsonArray
                    ?: throw IllegalArgumentException("golden bundle has no entries")
            for (item in evidenceEntries) {
                val evidence = item.jsonObject
                val name =
                    evidence["path"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("golden entry path missing")
                val expected =
                    evidence["sha256"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("golden entry SHA-256 missing")
                val entry = zip.getEntry(name) ?: throw IllegalArgumentException("golden entry missing: $name")
                val actual = zip.getInputStream(entry).use(::sha256)
                require(actual == expected) { "golden entry SHA-256 mismatch: $name" }
                if (name.startsWith("gateway/") && name.endsWith(".jsonl")) {
                    zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            val record = Json.parseToJsonElement(line).jsonObject
                            val method = record["method"]?.jsonPrimitive?.contentOrNull
                            val endpoint = record["path"]?.jsonPrimitive?.contentOrNull?.substringBefore('?')
                            if (method == "POST" && endpoint in MUTATING_MT5_PATHS && isUnknownMutation(record)) {
                                unknownOutcomes += 1L
                            }
                        }
                    }
                }
            }
        }
        return GoldenEvidence(unknownOutcomes)
    }

    private fun isUnknownMutation(record: kotlinx.serialization.json.JsonObject): Boolean {
        val responseCode = record["responseCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (responseCode == null || responseCode == 409 || responseCode >= 500) return true
        if (responseCode !in 200..299) return false
        val responseBody = record["responseBody"]?.jsonPrimitive?.contentOrNull ?: return true
        return runCatching {
            val result = Json.parseToJsonElement(responseBody).jsonObject["result"]?.jsonObject
            result
                ?.get("retcode")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull() == null
        }.getOrDefault(true)
    }

    private fun render(
        strategy: String,
        testingSha: String,
        image: String,
        startedAt: Instant,
        completedAt: Instant,
        tradingDays: Int,
        healthSamples: Long,
        parity: ParityEvidence,
        healthArtifact: Path,
        journalArtifact: Path,
        reconciliationArtifact: Path,
        coverageArtifact: Path,
        parityArtifact: Path,
        insightsArtifact: Path,
    ): String =
        buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"testingSha\": ").append(json(testingSha)).append(",\n")
            append("  \"image\": ").append(json(image)).append(",\n")
            append("  \"accountMode\": \"demo\",\n")
            append("  \"canaryStrategy\": ").append(json(strategy)).append(",\n")
            append("  \"startedAtUtc\": ").append(json(startedAt.toString())).append(",\n")
            append("  \"completedAtUtc\": ").append(json(completedAt.toString())).append(",\n")
            append("  \"tradingDays\": ").append(tradingDays).append(",\n")
            append("  \"status\": \"pass\",\n")
            append("  \"metrics\": {")
            append("\"unreconciledPositions\":0,")
            append("\"unknownOutcomePlacements\":0,")
            append("\"droppedTicks\":0,")
            append("\"healthSamples\":").append(healthSamples).append("},\n")
            append("  \"parity\": ").append(parity.root.toString()).append(",\n")
            append("  \"artifacts\": {")
            append("\"health\":").append(json(healthArtifact.fileName.toString())).append(',')
            append("\"journal\":").append(json(journalArtifact.fileName.toString())).append(',')
            append("\"reconciliation\":").append(json(reconciliationArtifact.fileName.toString())).append(',')
            append("\"coverage\":").append(json(coverageArtifact.fileName.toString())).append(',')
            append("\"parity\":").append(json(parityArtifact.fileName.toString())).append(',')
            append("\"insights\":").append(json(insightsArtifact.fileName.toString())).append("},\n")
            append("  \"artifactSha256\": {")
            append("\"health\":").append(json(sha256(healthArtifact))).append(',')
            append("\"journal\":").append(json(sha256(journalArtifact))).append(',')
            append("\"reconciliation\":").append(json(sha256(reconciliationArtifact))).append(',')
            append("\"coverage\":").append(json(sha256(coverageArtifact))).append(',')
            append("\"parity\":").append(json(sha256(parityArtifact))).append(',')
            append("\"insights\":").append(json(sha256(insightsArtifact))).append("}\n")
            append("}\n")
        }

    private fun copyArtifact(
        source: Path,
        target: Path,
    ) {
        if (source.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()) return
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        makeOwnerOnly(target)
    }

    private fun writeAtomic(
        output: Path,
        content: String,
    ) {
        val temporary = output.resolveSibling(".${output.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC,
            )
            makeOwnerOnly(temporary)
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun required(name: String): String =
        args.option(name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ArgError("missing required flag --$name")

    private fun parseObject(
        text: String,
        path: Path,
        line: Long,
    ) = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("malformed JSON at $path:$line: ${error.message}")
    }

    private fun sha256(path: Path): String = Files.newInputStream(path).use(::sha256)

    private fun makeOwnerOnly(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun json(value: String): String =
        buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun printUsage() {
        System.err.println(
            "usage: qkt soak report <strategy> --testing-sha <sha> --image <repo@sha256> " +
                "--started-at <instant> --completed-at <instant> --trading-days <n> " +
                "--health <jsonl> --reconciliation <json> --golden <zip> " +
                    "--coverage <json> --parity <json> --insights <json> --out <json>",
        )
    }

    private data class HealthEvidence(
        val samples: Long,
        val droppedTicks: Long,
    )

    private data class GoldenEvidence(
        val unknownOutcomePlacements: Long,
    )

    private data class ParityEvidence(
        val durationMinutes: Long,
        val timeframes: List<String>,
        val root: kotlinx.serialization.json.JsonObject,
    )

    companion object {
        private val MUTATING_MT5_PATHS =
            setOf("/order", "/close_position", "/position_close_partial", "/modify_sl_tp", "/cancel_order")
    }
}
