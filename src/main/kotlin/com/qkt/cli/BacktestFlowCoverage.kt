package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.cli.BacktestContext.Companion.SetupError
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.WarmupRequirements
import com.qkt.marketdata.flow.FlowCoverage
import com.qkt.marketdata.flow.TapeStore
import java.nio.file.Path
import java.time.Instant

/**
 * Refuses a backtest whose strategies read trade flow (`<alias>.buy_volume[n]`, ...) on days the data root does not
 * store ([FlowCoverage]), naming the `qkt fetch --tape` or `--liquidations` that would: without them the fields
 * would stay Undefined and read as a strategy that never found a setup. A read reaches back from the run's start by
 * the stream's warmup and its lookback, so the days before it that those windows fall on are required too.
 */
internal object BacktestFlowCoverage {
    /** Fails setup when [strategyAsts]' flow reads are not stored under [dataRoot] for [from]..[to]. */
    fun require(
        dataRoot: Path,
        strategyAsts: List<StrategyAst>,
        from: Instant,
        to: Instant,
    ) {
        val reads =
            strategyAsts.flatMap { ast ->
                val streams = ast.streams.associateBy { it.alias }
                val warmup = WarmupRequirements.compute(ast)
                StrategyDataRequirementScanner.scan(ast).flowLookbacks.mapNotNull { (read, back) ->
                    val (alias, kind) = read
                    val stream = streams[alias] ?: return@mapNotNull null
                    val windowMs = TimeWindow.parse(stream.timeframe).durationMs
                    val reach = (warmup[alias] ?: 0) + back + 1
                    FlowCoverage.Read(stream.qktSymbol, kind, from.toEpochMilli() - reach * windowMs)
                }
            }
        if (reads.isEmpty()) return
        FlowCoverage.problem(TapeStore(dataRoot), reads, to.toEpochMilli())?.let { throw SetupError(it) }
    }
}
