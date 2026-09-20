package com.qkt.cli

import com.qkt.cli.soak.copyArtifact
import com.qkt.cli.soak.inspectGolden
import com.qkt.cli.soak.inspectHealth
import com.qkt.cli.soak.inspectJsonArtifact
import com.qkt.cli.soak.inspectParity
import com.qkt.cli.soak.inspectReconciliation
import com.qkt.cli.soak.renderSoakReport
import com.qkt.cli.soak.writeAtomic
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

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
                renderSoakReport(
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

    private fun required(name: String): String =
        args.option(name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ArgError("missing required flag --$name")

    private fun printUsage() {
        System.err.println(
            "usage: qkt soak report <strategy> --testing-sha <sha> --image <repo@sha256> " +
                "--started-at <instant> --completed-at <instant> --trading-days <n> " +
                "--health <jsonl> --reconciliation <json> --golden <zip> " +
                "--coverage <json> --parity <json> --insights <json> --out <json>",
        )
    }
}
