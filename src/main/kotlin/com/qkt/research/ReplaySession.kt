package com.qkt.research

import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseError
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant

/** Current replay position + running totals, rendered as the REPL footer. */
data class Footer(
    val timestamp: Long,
    val barsClosed: Long,
    val tradeCount: Int,
    val equity: BigDecimal,
    val openPositions: Map<String, Position>,
    val exhausted: Boolean,
)

/** What one dispatched command produced: new tape lines, the footer, and any notice/errors. */
data class StepResult(
    val tape: List<TapeEvent>,
    val footer: Footer,
    val notice: String? = null,
    val reloadErrors: List<ParseError> = emptyList(),
    val quit: Boolean = false,
)

/**
 * An interactive replay over a fixed historical window. Holds the ticks in memory once;
 * [ReplayCommand.Reset] rebuilds the engine from those cached ticks and
 * [ReplayCommand.Reload] re-reads + recompiles the strategy file before resetting.
 *
 * e.g. `dispatch(StepBars(3))` advances three primary-timeframe bars and returns the
 * fills/signals seen during those bars plus the new equity/position footer.
 */
class ReplaySession(
    private val ticks: List<Tick>,
    private val strategyPath: Path,
    private val startingBalance: BigDecimal,
    private val instruments: InstrumentRegistry,
) {
    private var ast: StrategyAst = parseOrThrow()
    private var engine: ReplayEngine = buildEngine()

    private val candleWindow: TimeWindow?
        get() =
            ast.streams
                .firstOrNull()
                ?.timeframe
                ?.let { TimeWindow.parse(it) }

    private fun parseOrThrow(): StrategyAst =
        when (val r = Dsl.parseFile(strategyPath)) {
            is ParseResult.Success -> r.value
            is ParseResult.Failure ->
                error("cannot compile $strategyPath: ${r.errors.joinToString { "${it.line}:${it.col} ${it.message}" }}")
        }

    private fun buildEngine(): ReplayEngine {
        val strategy = AstCompiler().compile(ast)
        val from = ticks.firstOrNull()?.timestamp ?: 0L
        val window = candleWindow
        return ReplayEngine(
            strategies = listOf(ast.name to strategy),
            feed = HistoricalTickFeed(ticks),
            candleWindow = window,
            initialTimestamp = from,
            symbols = ast.streams.map { it.symbol }.distinct(),
            cadence = if (window != null) SampleCadence.CANDLE_CLOSE else SampleCadence.TICK,
            startingBalance = startingBalance,
            instruments = instruments,
        )
    }

    private fun reset() {
        engine = buildEngine()
    }

    /** Execute one command and return its result. Never throws on user input. */
    fun dispatch(cmd: ReplayCommand): StepResult =
        when (cmd) {
            is ReplayCommand.Run -> {
                engine.advanceToEnd()
                advanced()
            }
            is ReplayCommand.StepBars -> {
                if (candleWindow != null) {
                    val target = engine.barsClosed + cmd.n
                    engine.advanceUntil { engine.barsClosed >= target }
                } else {
                    val target = engine.ticksIngested + cmd.n
                    engine.advanceUntil { engine.ticksIngested >= target }
                }
                advanced()
            }
            is ReplayCommand.StepDuration -> {
                val target = engine.currentTimestamp + cmd.millis
                engine.advanceUntil { engine.currentTimestamp >= target }
                advanced()
            }
            is ReplayCommand.RunToTime -> {
                if (cmd.epochMillis <= engine.currentTimestamp) {
                    reset()
                    engine.advanceUntil { engine.currentTimestamp >= cmd.epochMillis }
                    advanced(notice = "reset and ran forward to ${Instant.ofEpochMilli(cmd.epochMillis)}")
                } else {
                    engine.advanceUntil { engine.currentTimestamp >= cmd.epochMillis }
                    advanced()
                }
            }
            is ReplayCommand.RunToNextTrade -> {
                val target = engine.tradeCount + 1
                engine.advanceUntil { engine.tradeCount >= target }
                advanced()
            }
            is ReplayCommand.Reset -> {
                reset()
                advanced(notice = "replay reset to start")
            }
            is ReplayCommand.Reload -> reload()
            is ReplayCommand.Show -> StepResult(tape = emptyList(), footer = footer())
            is ReplayCommand.Quit -> StepResult(tape = emptyList(), footer = footer(), quit = true)
            is ReplayCommand.Unknown ->
                StepResult(
                    tape = emptyList(),
                    footer = footer(),
                    notice =
                        "unknown command: '${cmd.input}' (try: run, step N, step 1d, " +
                            "run-to <time>, run-to next-trade, reset, reload, show, quit)",
                )
        }

    private fun reload(): StepResult =
        when (val r = Dsl.parseFile(strategyPath)) {
            is ParseResult.Success -> {
                ast = r.value
                reset()
                advanced(notice = "recompiled ${strategyPath.fileName}, replay reset to start")
            }
            is ParseResult.Failure ->
                StepResult(tape = emptyList(), footer = footer(), reloadErrors = r.errors)
        }

    private fun advanced(notice: String? = null) =
        StepResult(tape = engine.drainTape(), footer = footer(), notice = notice)

    private fun footer() =
        Footer(
            timestamp = engine.currentTimestamp,
            barsClosed = engine.barsClosed,
            tradeCount = engine.tradeCount,
            equity = engine.equity(),
            openPositions = engine.openPositions(),
            exhausted = engine.exhausted,
        )
}
