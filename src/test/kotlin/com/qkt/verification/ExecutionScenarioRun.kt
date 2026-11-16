package com.qkt.verification

import com.qkt.cli.Args
import com.qkt.cli.BacktestCommand
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One fill as `trades.csv` reports it, reduced to what the oracle tape states. */
data class ActualFill(
    val ts: Long,
    val side: String,
    val qty: BigDecimal,
    val price: BigDecimal,
    val realized: BigDecimal,
    val reason: String,
    val orderType: String,
    val stopLoss: BigDecimal?,
    val takeProfit: BigDecimal?,
)

/** What one `qkt backtest` of a scenario produced: its tape and the headline counts from `--json`. */
data class ExecutionRunResult(
    val fills: List<ActualFill>,
    val rejections: Int,
    val finalRealized: BigDecimal,
    val commissionPaid: BigDecimal,
    val stdout: String,
)

/**
 * Runs a scenario through `BacktestCommand`, the same entry point as `qkt backtest`: the ticks become a
 * `symbols/<SYM>/<day>.csv` day file, the instrument an `instruments.yaml`, and the fill mode the CLI flags
 * (`--broker`, `--position-mode`, `--bars --no-tick-fills` after `qkt data build-bars`, or `--bars --tick-fills`).
 */
object ExecutionScenarioRun {
    private const val FROM = "2024-01-10"
    private const val TO = "2024-01-11"

    fun run(
        scenario: ExecutionScenario,
        dir: Path,
    ): ExecutionRunResult {
        val dataRoot = dir.resolve("data")
        seedTicks(scenario, dataRoot)
        val strategy = dir.resolve("${scenario.name}.qkt")
        Files.writeString(strategy, ExecutionScenarios.strategy(scenario.name))
        val instruments = dir.resolve("instruments.yaml")
        Files.writeString(instruments, instrumentsYaml(scenario))
        val reportDir = dir.resolve("report")
        val flags = mutableListOf<String>()
        if (scenario.broker == "mt5-sim") flags += listOf("--broker", "mt5-sim")
        if (scenario.positionMode == "netting") flags += listOf("--position-mode", "netting")
        when (scenario.fillMode) {
            "ticks" -> Unit
            "bars-synthetic" -> {
                buildBars(scenario, dataRoot)
                flags += listOf("--bars", "--no-tick-fills")
            }
            "bars-ticks" -> {
                buildBars(scenario, dataRoot)
                flags += listOf("--bars", "--tick-fills")
            }
            else -> error("${scenario.name}: unknown fillMode ${scenario.fillMode}")
        }
        val args =
            listOf(
                "backtest",
                strategy.toString(),
                "--from",
                FROM,
                "--to",
                TO,
                "--data-root",
                dataRoot.toString(),
                "--no-fetch",
                "--allow-incomplete",
                "--json",
                "--report-dir",
                reportDir.toString(),
                "--instruments",
                instruments.toString(),
                "--starting-balance",
                scenario.startingBalance,
            ) + flags
        val out = capture { BacktestCommand(Args(args.toTypedArray())).run() }
        check(out.first == ExitCodes.SUCCESS) { "${scenario.name} exited ${out.first}: ${out.second}" }
        val line =
            out.second
                .lineSequence()
                .map(String::trim)
                .single { it.startsWith("{") }
        val summary = Json.parseToJsonElement(line).jsonObject
        val tradeSummary = summary.getValue("tradeSummary").jsonObject
        return ExecutionRunResult(
            fills = TradesCsvTape.read(reportDir.resolve("trades.csv")),
            rejections =
                tradeSummary
                    .getValue("rejections")
                    .jsonPrimitive.content
                    .toInt(),
            finalRealized = BigDecimal(summary.getValue("finalRealized").jsonPrimitive.content),
            commissionPaid = BigDecimal(summary.getValue("commissionPaid").jsonPrimitive.content),
            stdout = out.second,
        )
    }

    private fun seedTicks(
        scenario: ExecutionScenario,
        dataRoot: Path,
    ) {
        val t0 = Instant.parse(scenario.t0).toEpochMilli()
        val rows =
            scenario.ticks.map { (sec, bid, ask) ->
                "${t0 + sec.content.toLong() * 1000},${scenario.symbol},,,${bid.content},${ask.content},,"
            }
        val file = dataRoot.resolve("symbols/${scenario.symbol}/$FROM.csv")
        Files.createDirectories(file.parent)
        val header = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
        Files.writeString(file, header + "\n" + rows.joinToString("\n") + "\n")
    }

    private fun instrumentsYaml(scenario: ExecutionScenario): String =
        buildString {
            appendLine("instruments:")
            appendLine("  - qktSymbol: BACKTEST:${scenario.symbol}")
            scenario.instrument.forEach { (key, value) ->
                if (value != null && value !is JsonNull) appendLine("    $key: ${value.content}")
            }
            appendLine("    tradeStopsLevelPoints: 0")
        }

    private fun buildBars(
        scenario: ExecutionScenario,
        dataRoot: Path,
    ) {
        val tf = "${scenario.timeframeSec / 60}m"
        val args =
            arrayOf(
                "data",
                "build-bars",
                scenario.symbol,
                "--tf",
                tf,
                "--from",
                FROM,
                "--to",
                TO,
                "--data-root",
                dataRoot.toString(),
            )
        val out = capture { DataCommand(Args(args)).run() }
        check(out.first == ExitCodes.SUCCESS) { "${scenario.name}: build-bars exited ${out.first}: ${out.second}" }
    }

    private fun capture(block: () -> Int): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        val code =
            try {
                System.setOut(PrintStream(buffer))
                block()
            } finally {
                System.setOut(original)
            }
        return code to buffer.toString()
    }
}
