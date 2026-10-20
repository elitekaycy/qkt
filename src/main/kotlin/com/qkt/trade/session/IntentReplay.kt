package com.qkt.trade.session

import com.qkt.backtest.BacktestResult
import com.qkt.research.ReplayEngine

/**
 * Runs a session's journaled intents again over a backtest [session]. A backtest record re-injects each
 * intent once the engine has handed the session as many ticks as when it arrived ([JournaledIntent.tickSeq]),
 * so the replay is the same run. A live record replayed over the ticks it recorded is injected the same way
 * (they are the ticks it was handed, in order); over the store it re-injects each at the first stored tick
 * at or after its arrival time (live ticks are not the stored ticks, so fills can differ, and the diff says where).
 */
class IntentReplay(
    private val engine: ReplayEngine,
    private val recorder: BotSessionRecorder,
    private val session: BotRunSession,
) {
    fun run(
        intents: List<JournaledIntent>,
        byArrivalTime: Boolean,
        finishedAt: Pair<Long, Long>? = null,
    ): BacktestResult {
        for (intent in intents) {
            if (byArrivalTime) {
                if (engine.currentTimestamp <
                    intent.simMs
                ) {
                    engine.advanceUntil { engine.currentTimestamp >= intent.simMs }
                }
            } else if (recorder.ticksSeen < intent.tickSeq) {
                // advanceUntil always takes a tick first: advance only when the intent arrived further on.
                engine.advanceUntil { recorder.ticksSeen >= intent.tickSeq }
            }
            session.submit(intent.identity, intent.source)
        }
        // Run to where the recorded session was finished (its stops and targets past it never fired there).
        when {
            finishedAt == null -> engine.advanceToEnd()
            byArrivalTime ->
                if (engine.currentTimestamp <
                    finishedAt.first
                ) {
                    engine.advanceUntil { engine.currentTimestamp >= finishedAt.first }
                }
            recorder.ticksSeen < finishedAt.second -> engine.advanceUntil { recorder.ticksSeen >= finishedAt.second }
        }
        return requireNotNull(session.finish()) { "a replay runs a backtest session" }
    }
}
