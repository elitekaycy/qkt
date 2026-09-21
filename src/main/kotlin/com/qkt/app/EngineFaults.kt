package com.qkt.app

import com.qkt.risk.RiskState
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

/**
 * What the engine loop does when a handler throws. A strategy/indicator/handler exception must
 * never kill the engine thread silently: log with full context, raise a CRITICAL alert, halt
 * the session's trading (PERSISTENT — an operator resumes after diagnosing), and keep draining
 * the queue so exits, halts, and flattens still work, e.g. an indicator dividing by zero on one
 * tick halts the session with "engine fault: tick EXNESS:XAUUSD@1789950000000: / by zero".
 */
internal class EngineFaults(
    private val strategies: List<Pair<String, Strategy>>,
    private val riskState: RiskState,
    private val sessionNotifier: SessionNotifier,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /** Record the fault raised during [stage], halt trading and alert the operator. */
    fun onEngineFault(
        stage: String,
        t: Throwable,
    ) {
        log.error("engine loop fault during {} — halting trading, loop stays alive", stage, t)
        runCatching { riskState.halt("engine fault: $stage: ${t.message}") }
        sessionNotifier.strategyError(strategies.firstOrNull()?.first.orEmpty(), "StrategyError") {
            "engine loop fault during $stage: $t"
        }
    }
}
