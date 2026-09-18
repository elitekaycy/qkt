package com.qkt.app

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.ScheduleRunner
import com.qkt.dsl.compile.isObservationSymbol
import com.qkt.engine.Engine
import com.qkt.marketdata.MarketDataGate
import com.qkt.marketdata.Tick
import com.qkt.strategy.Mode
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * The pipeline's tick entry point: drops malformed and implausible ticks, hands the rest to the
 * engine, samples the equity series, closes replay candles by event time, and feeds the candle hub
 * and schedule runner. The same path runs in backtest and live so the gates cannot diverge.
 */
internal class TickIngest(
    private val engine: Engine,
    /**
     * Runtime market-data judgment (#395). Non-null in live sessions; backtests leave
     * it null — deterministic historical replay is exactly the data it was given.
     */
    private val marketDataGate: MarketDataGate?,
    private val equitySampler: AccountEquitySeriesSampler,
    private val candleCloser: CandleWindowCloser,
    private val candleHub: CandleHub,
    private val scheduleRunner: ScheduleRunner,
    private val mode: Mode,
) {
    // Logged under the pipeline's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    /** Count of ticks dropped by [ingest]'s validation floor. */
    val malformedTickCount = AtomicLong(0)

    /** Ingest one tick. */
    fun ingest(tick: Tick) {
        val isMacroObservation = isObservationSymbol(tick.symbol)
        // Hard floor on the most exposed input boundary the engine has: one glitched
        // tick (zero/negative price, crossed quotes) marks every open position wrong,
        // fires engine-held triggers, and poisons indicators for a full window. Drop
        // it, count it, keep the last good price (#379). Identical in backtest and
        // live so the gate itself cannot cause divergence.
        if (!isMacroObservation && !isValidTick(tick)) {
            val n = malformedTickCount.incrementAndGet()
            if (n == 1L || n % MALFORMED_TICK_LOG_EVERY == 0L) {
                log.error(
                    "dropping malformed tick #{} for {}: price={} bid={} ask={}",
                    n,
                    tick.symbol,
                    tick.price.toPlainString(),
                    tick.bid?.toPlainString(),
                    tick.ask?.toPlainString(),
                )
            }
            return
        }
        // The judgment layer above the floor: an implausible (outlier/crossed) tick is
        // dropped before it can poison indicators, marks, or triggers (#395).
        if (!isMacroObservation &&
            marketDataGate?.observe(tick) == MarketDataGate.Verdict.OUTLIER
        ) {
            return
        }
        engine.onTick(tick)
        equitySampler.sample(tick.timestamp)
        // Replay has no wall clock, so event time is the clock. A quiet symbol's ended bar
        // closes on the first tick of any symbol at or past the heartbeat step that live would
        // close it on: the first 1 Hz step at least the grace after the window end (#1134,
        // #1138). Never at the tick's own instant, because ticks sharing one timestamp arrive
        // together live and a tick cannot know whether more of its instant follow; a symbol's
        // own boundary tick therefore still closes its bar through the feed below, after this
        // TickEvent, so it fills against that tick exactly as before.
        if (mode == Mode.BACKTEST) candleCloser.flushReplayAt(tick.timestamp)
        candleHub.feed(tick)
        scheduleRunner.tick(tick.timestamp)
    }

    private companion object {
        /** Log cadence for malformed-tick drops — first occurrence, then every Nth. */
        const val MALFORMED_TICK_LOG_EVERY: Long = 1000L
    }
}

private fun isValidTick(tick: Tick): Boolean {
    if (tick.price.signum() <= 0) return false
    val bid = tick.bid
    val ask = tick.ask
    if (bid != null && bid.signum() <= 0) return false
    if (ask != null && ask.signum() <= 0) return false
    if (bid != null && ask != null && bid > ask) return false
    return true
}
