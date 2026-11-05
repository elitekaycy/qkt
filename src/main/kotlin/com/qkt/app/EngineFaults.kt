package com.qkt.app

import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

/**
 * What the engine loop does when a handler throws. A strategy/indicator/handler exception must
 * never kill the engine thread silently: log with full context, raise a CRITICAL alert, halt
 * the session's trading (PERSISTENT — an operator resumes after diagnosing; TRANSIENT for a JVM
 * resource fault such as an OutOfMemoryError, which a restart cures), and keep draining
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
        // A failed allocation (OutOfMemoryError, any VirtualMachineError) is the JVM's fault, not the strategy's: the
        // halt lasts for this process only, so it ends with the restart that cures it instead of outliving it (#1645).
        val scope = if (t is VirtualMachineError) HaltScope.TRANSIENT else HaltScope.PERSISTENT
        runCatching { riskState.halt("engine fault: $stage: ${t.message}", scope) }
        sessionNotifier.strategyError(strategies.firstOrNull()?.first.orEmpty(), "StrategyError") {
            "engine loop fault during $stage: $t"
        }
    }
}
