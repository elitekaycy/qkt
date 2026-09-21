package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotBuy
import com.qkt.dsl.ast.SnapshotOpen
import com.qkt.dsl.ast.SnapshotSell
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

class CompiledRule(
    private val condition: CompiledExpr,
    private val action: (EvalContext) -> List<Signal>,
    val ruleAlias: String,
    private val ruleSymbol: String,
    private val isBuy: Boolean,
    private val isSell: Boolean,
    private val onBuyCaptures: List<Pair<String, CompiledExpr>>,
    private val onSellCaptures: List<Pair<String, CompiledExpr>>,
    private val onOpenCaptures: List<Pair<String, CompiledExpr>>,
    val referencedAliases: Set<String>,
    internal val conditionFingerprint: String = "",
    internal val ruleFingerprint: String = "",
    val consumesSequenceCompletion: Boolean = false,
    internal val edgeStateKey: String = ruleAlias,
    /** What the condition requires of the position on the rule's own symbol; see [PositionGate]. */
    internal val positionGate: PositionGate = PositionGate.NONE,
) {
    internal val ruleId: String
        get() = edgeStateKey

    // Action gating is edge-driven (reference/dsl/conditions.md): the action runs on the
    // false->true transition of the condition, not on every bar it stays true. Without
    // this, `WHEN close > ema THEN BUY` emits an entry on all 50 bars the close holds
    // above the EMA. Undefined counts as false, so a condition that goes true ->
    // Undefined -> true re-arms and fires again.
    private var wasTrue = false
    private var pendingCommit = false
    private var rejectedDuringCommit = false
    private var edgeDirty = false
    private var openedDuringCommit = false

    // When the position last went flat, for a rule gated on being flat. A bar is judged on the
    // world as it stood when that bar ended.
    private var gateSatisfiedSinceMs: Long? = null

    internal val edgeState: Boolean
        get() = wasTrue

    internal fun restoreEdgeState(value: Boolean) {
        wasTrue = value
        edgeDirty = false
    }

    /**
     * Forget the edge, as on a fresh deployment. Used when an operator stop flattened the
     * position this edge's fire opened: the next bar the condition holds fires again.
     */
    internal fun clearEdge() {
        if (wasTrue) edgeDirty = true
        wasTrue = false
        pendingCommit = false
        rejectedDuringCommit = false
        openedDuringCommit = false
    }

    /**
     * The strategy's position on [symbol] flipped between flat and held. A rule gated on the state
     * it just left is false from this moment, whether or not a bar closes before the state flips
     * back: without this, a bracket stopped out before the next evaluation leaves its entry
     * condition true at both evaluations, no edge occurs, and the entry never fires again (#1194).
     */
    internal fun onPositionStateChanged(
        symbol: String,
        nowHeld: Boolean,
        atMs: Long,
    ) {
        if (symbol != ruleSymbol || positionGate == PositionGate.NONE) return
        val nowFalse = if (nowHeld) positionGate == PositionGate.FLAT else positionGate == PositionGate.HELD
        if (!nowFalse) {
            // Only entries wait for the next close. An exit gated on holding may act on the bar its
            // entry filled on, as it always has: reducing risk early is the safe side of the race.
            if (positionGate == PositionGate.FLAT) gateSatisfiedSinceMs = atMs
            return
        }
        gateSatisfiedSinceMs = null
        // A simulated fill lands inside this rule's own fire, before the edge is sealed; a venue
        // fill lands after. Either way the edge must end up reset.
        if (pendingCommit) openedDuringCommit = true else clearEdge()
    }

    internal fun consumeEdgeDirty(): Boolean {
        val dirty = edgeDirty
        edgeDirty = false
        return dirty
    }

    fun fire(
        ec: EvalContext,
        ctx: StrategyContext,
    ): List<Signal> {
        val v = condition.evaluate(ec)
        // The tick that closes a bar can also be the one that stops the position out. The bar ended
        // first: as of its close the position was still open, so an entry gated on being flat does
        // not fire off that bar - it would be re-entering on a close that predates its own exit,
        // e.g. a 4h bar closes at 100, the next tick gaps to 111 through the target, and the rule
        // would buy at 111 because "the bar closed at 100 and I am flat". It waits for the next close.
        val gateMetAfterBar = gateSatisfiedSinceMs?.let { it >= ec.candle.endTime } ?: false
        val isTrue = v is Value.Bool && v.v && !gateMetAfterBar
        if (!isTrue) {
            if (wasTrue) edgeDirty = true
            wasTrue = false
            pendingCommit = false
            rejectedDuringCommit = false
            return emptyList()
        }
        if (wasTrue) return emptyList()
        // Rising edge. The edge is only sealed by [commitFire] once the caller knows
        // whether any produced signal was actually accepted for submission.
        pendingCommit = true

        val preFireQty =
            ctx.positions.positionFor(ruleSymbol)?.quantity ?: BigDecimal.ZERO
        val isOpening = preFireQty.signum() == 0 && (isBuy || isSell)

        if (isBuy) capture(onBuyCaptures, SnapshotBuy, ec)
        if (isSell) capture(onSellCaptures, SnapshotSell, ec)
        if (isOpening) capture(onOpenCaptures, SnapshotOpen, ec)

        return action(ec)
    }

    /**
     * Seals the edge begun by the last rising [fire]. With [accepted] true the rule stays
     * quiet while the condition holds (normal edge gating); with false the edge re-arms so
     * the rule fires again on the next bar the condition still holds. Callers pass false
     * when every signal of the fire was suppressed before reaching the venue (portfolio
     * gate inactive, book de-risk, risk reject) — losses a backtest never sees, so without
     * the re-arm live silently drops entries the backtest takes. No-op without a pending fire.
     */
    internal fun commitFire(accepted: Boolean): RuleCommitOutcome? {
        if (!pendingCommit) return null
        pendingCommit = false
        val committed = accepted && !rejectedDuringCommit
        rejectedDuringCommit = false
        val sealed = committed && !openedDuringCommit
        openedDuringCommit = false
        if (wasTrue != sealed) edgeDirty = true
        wasTrue = sealed
        return if (committed) RuleCommitOutcome.ACCEPTED else RuleCommitOutcome.REARMED
    }

    /** Re-arm this edge after the venue or pre-trade gate rejects its emitted order. */
    internal fun rearmAfterRejection() {
        if (pendingCommit) {
            rejectedDuringCommit = true
        } else {
            if (wasTrue) edgeDirty = true
            wasTrue = false
        }
    }

    private fun capture(
        captures: List<Pair<String, CompiledExpr>>,
        kind: com.qkt.dsl.ast.SnapshotKind,
        ec: EvalContext,
    ) {
        for ((name, e) in captures) {
            val r = e.evaluate(ec)
            if (r is Value.Num) ec.snapshotStore.captureSlot(ruleAlias, name, kind, r.v)
        }
    }
}

internal enum class RuleCommitOutcome {
    ACCEPTED,
    REARMED,
}
