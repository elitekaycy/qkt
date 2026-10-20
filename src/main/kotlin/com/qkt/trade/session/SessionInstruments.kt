package com.qkt.trade.session

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * The venue's instrument spec a live session traded on, kept in its record as an `instruments.yaml`. A replay reads
 * it, so a live record replays on any machine; without it the replay needed an instruments file that happened to
 * cover the symbol, and refused every order where none did.
 */
object SessionInstruments {
    const val FILE = "instruments.yaml"

    /** What a venue states about a symbol, in instruments.yaml's terms. */
    data class Spec(
        val contractSize: BigDecimal,
        val volumeStep: BigDecimal,
        val volumeMin: BigDecimal,
        val volumeMax: BigDecimal?,
        val digits: Int,
    )

    fun write(
        path: Path,
        specs: Map<String, Spec>,
    ) {
        if (specs.isEmpty()) return
        val text =
            buildString {
                appendLine("# The venue's instrument spec this session traded on, written by qkt bot at finish.")
                appendLine("instruments:")
                for ((symbol, s) in specs.toSortedMap()) {
                    appendLine("  - qktSymbol: \"$symbol\"")
                    appendLine("    contractSize: ${s.contractSize.stripTrailingZeros().toPlainString()}")
                    appendLine("    volumeStep: ${s.volumeStep.stripTrailingZeros().toPlainString()}")
                    appendLine("    volumeMin: ${s.volumeMin.stripTrailingZeros().toPlainString()}")
                    s.volumeMax?.let { appendLine("    volumeMax: ${it.stripTrailingZeros().toPlainString()}") }
                    appendLine("    pointSize: ${BigDecimal.ONE.movePointLeft(s.digits).toPlainString()}")
                    appendLine("    digits: ${s.digits}")
                    appendLine("    tradeStopsLevelPoints: 0")
                }
            }
        Files.writeString(path, text)
    }

    /** `--instruments <record spec>` for a replay of [dir], unless [args] name one or the record has none. */
    fun replayArgs(
        dir: Path,
        args: com.qkt.cli.Args,
    ): List<String> {
        val file = dir.resolve(FILE)
        val given = args.option("instruments")
        return if (given == null && Files.isRegularFile(file)) listOf("--instruments", file.toString()) else emptyList()
    }
}
