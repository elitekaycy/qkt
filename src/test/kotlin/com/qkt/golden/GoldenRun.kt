package com.qkt.golden

import com.qkt.backtest.TickResolvedParityFixtures
import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.BinaryTickWriter
import com.qkt.marketdata.Tick
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Runs one [GoldenCase] offline and returns its normalized outputs. */
internal object GoldenRun {
    /** Evidence keys that name the build or the machine, never the result. */
    private val runVaryingEvidence = setOf("qktVersion", "gitSha", "buildTimestamp", "command", "importedFileHashes")

    private val pretty = Json { prettyPrint = true }

    /** Normalized report JSON and the raw trades CSV of [case]. */
    fun run(
        case: GoldenCase,
        dir: Path,
    ): Pair<String, String> {
        val dataRoot = dir.resolve("data")
        seed(case, dataRoot)
        val strategy = dir.resolve("${case.name}.qkt").also { Files.writeString(it, case.strategy) }
        val reportDir = dir.resolve("report")
        val instruments =
            case.instruments?.let { yaml ->
                listOf("--instruments", dir.resolve("instruments.yaml").also { Files.writeString(it, yaml) }.toString())
            } ?: emptyList()
        val out = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(out))
                BacktestCommand(
                    Args(
                        (
                            listOf(
                                "backtest",
                                strategy.toString(),
                                "--from",
                                case.from,
                                "--to",
                                case.to,
                                "--data-root",
                                dataRoot.toString(),
                                "--no-fetch",
                                "--allow-incomplete",
                                "--json",
                                "--report-dir",
                                reportDir.toString(),
                            ) + instruments + case.flags
                        ).toTypedArray(),
                    ),
                ).run()
            } finally {
                System.setOut(original)
            }
        check(code == ExitCodes.SUCCESS) { "golden case ${case.name} exited $code: $out" }
        return normalize(out.toString()) to Files.readString(reportDir.resolve("trades.csv"))
    }

    /** The report line with build- and machine-specific evidence removed, pretty-printed. */
    fun normalize(stdout: String): String {
        val line = stdout.lineSequence().map(String::trim).single { it.startsWith("{") }
        val root = Json.parseToJsonElement(line).jsonObject
        val evidence = root["evidence"]
        val cleaned: JsonElement =
            if (evidence is JsonObject) {
                JsonObject(root + ("evidence" to JsonObject(evidence - runVaryingEvidence)))
            } else {
                root
            }
        return pretty.encodeToString(JsonElement.serializer(), cleaned) + "\n"
    }

    private fun seed(
        case: GoldenCase,
        dataRoot: Path,
    ) {
        when (case.data) {
            GoldenData.EURUSD_REAL_DAY -> {
                val target = dataRoot.resolve("symbols/EURUSD/2024-01-10.csv.gz")
                Files.createDirectories(target.parent)
                requireNotNull(javaClass.getResourceAsStream("/parity/dukascopy/eurusd-2024-01-10.csv.gz")) {
                    "EURUSD parity fixture is missing"
                }.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            }
            GoldenData.XAUUSD_SINE_3D -> TickResolvedParityFixtures.seedTicks(dataRoot, days = 3)
            GoldenData.USDJPY_SINE_3D -> seedSine(dataRoot, "USDJPY", base = 150.0, amplitude = 0.6, decimals = 3)
            GoldenData.BTCUSDT_SINE_3D ->
                seedSine(
                    dataRoot,
                    "BTCUSDT",
                    base = 42_000.0,
                    amplitude = 300.0,
                    decimals = 1,
                )
        }
        val tf = case.barsTimeframe ?: return
        val symbol = if (case.data == GoldenData.EURUSD_REAL_DAY) "EURUSD" else "XAUUSD"
        val built =
            DataCommand(
                Args(
                    arrayOf(
                        "data",
                        "build-bars",
                        symbol,
                        "--tf",
                        tf,
                        "--from",
                        case.from,
                        "--to",
                        case.to,
                        "--data-root",
                        dataRoot.toString(),
                    ),
                ),
            ).run()
        check(built == ExitCodes.SUCCESS) { "build-bars failed for ${case.name}" }
    }

    /** Three days of one tick per minute: `base + amplitude × sin(minute / 7)`, rounded to [decimals]. */
    private fun seedSine(
        dataRoot: Path,
        symbol: String,
        base: Double,
        amplitude: Double,
        decimals: Int,
    ) {
        val start = Instant.parse("2024-01-02T00:00:00Z").toEpochMilli()
        (0 until 3 * 1440)
            .map { m ->
                Tick(
                    symbol,
                    BigDecimal("%.${decimals}f".format(base + amplitude * sin(m / 7.0))),
                    start + m * 60_000L,
                )
            }.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestamp), ZoneOffset.UTC) }
            .forEach { (day, ticks) ->
                val f = dataRoot.resolve("symbols").resolve(symbol).resolve("$day.bin")
                Files.createDirectories(f.parent)
                BinaryTickWriter().write(f, symbol, ticks)
            }
    }
}
