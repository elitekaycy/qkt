package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.cli.BacktestContext.Companion.SetupError
import com.qkt.dsl.ast.StrategyAst
import com.qkt.marketdata.marks.MarkCoverage
import com.qkt.marketdata.marks.MarkStore
import java.nio.file.Path
import java.time.Instant

/**
 * Refuses a backtest whose strategies read a contract's mark or index (`<alias>.mark`, `.index`) on days
 * the data root does not store at the stream's window ([MarkCoverage]), naming the `qkt fetch --marks` that
 * would: without them the fields would stay Undefined for the whole run and read as a strategy that never
 * found a setup.
 */
internal object BacktestMarkCoverage {
    /** Fails setup when [strategyAsts]' mark reads are not stored under [dataRoot] for [from]..[to]. */
    fun require(
        dataRoot: Path,
        strategyAsts: List<StrategyAst>,
        from: Instant,
        to: Instant,
    ) {
        val reads =
            strategyAsts.flatMap { ast ->
                val streams = ast.streams.associateBy { it.alias }
                StrategyDataRequirementScanner
                    .scan(ast)
                    .markAliases
                    .mapNotNull { streams[it] }
                    .map { it.qktSymbol to TimeWindow.parse(it.timeframe).durationMs }
            }
        if (reads.isEmpty()) return
        MarkCoverage.problem(MarkStore(dataRoot), reads, from.toEpochMilli(), to.toEpochMilli())?.let {
            throw SetupError(it)
        }
    }
}
