package com.qkt.app

import com.qkt.persistence.PersistedStrategyHalt
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState

/**
 * What a session can say about being halted. A handle that cannot halt answers "no" to everything;
 * a live session answers from its risk state, e.g. `haltReason()` = "operator",
 * `haltScope()` = PERSISTENT, `haltedAtMs()` = 1789950000000.
 */
interface HaltReads {
    /** Reason for the active halt, or null when not halted/unsupported by this handle. */
    fun haltReason(): String? = null

    /** Scope for the active global halt, or null when not halted/unsupported by this handle. */
    fun haltScope(): HaltScope? = null

    /** When the active global halt tripped, epoch ms; null when not halted or not recorded. */
    fun haltedAtMs(): Long? = null

    /** Whether this session is currently halted (operator halt or a risk auto-halt). */
    fun isHalted(): Boolean = false

    /** Strategy-scoped halts held by this session's risk state; empty by default (#1064). */
    fun strategyHalts(): List<PersistedStrategyHalt> = emptyList()
}

/** [HaltReads] over a live session's risk state and the strategies it runs. */
internal class RiskHaltReads(
    private val riskState: RiskState,
    private val strategyIds: List<String>,
) : HaltReads {
    override fun haltReason(): String? =
        riskState.haltReason ?: strategyIds.firstNotNullOfOrNull { riskState.haltReasonFor(it) }

    override fun haltScope(): HaltScope? = riskState.globalHaltScope()

    override fun haltedAtMs(): Long? = riskState.globalHaltedAtMs()

    // A strategy-scoped halt (runaway breaker, per-strategy drawdown) blocks entries exactly
    // like a global one; status/health must not show the session as free (#1064).
    override fun isHalted(): Boolean = riskState.halted || strategyIds.any { riskState.strategyHalted(it) }

    override fun strategyHalts(): List<PersistedStrategyHalt> = riskState.strategyHalts()
}
