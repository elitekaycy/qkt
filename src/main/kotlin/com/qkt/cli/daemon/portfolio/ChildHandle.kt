package com.qkt.cli.daemon.portfolio

import com.qkt.cli.daemon.StrategyHandle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The runtime tuple a [PortfolioSupervisor] needs to manage one child strategy:
 * its [parent] portfolio name, [alias] within that portfolio, the underlying
 * [StrategyHandle], and two gating flags.
 *
 * - [gateActive] — `true` when the portfolio's WHEN-clause evaluates this child as
 *   eligible for trading. Flipped by the supervisor on each evaluation.
 * - [operatorStop] — sticky operator override; persists across gate evaluations
 *   until explicitly cleared via `qkt start <portfolio>/<alias>`.
 *
 * A child is effectively trading only when both conditions hold ([effectiveActive]).
 */
class ChildHandle(
    val parent: String,
    val alias: String,
    val hold: Boolean,
    val handle: StrategyHandle,
    val gateActive: AtomicBoolean = AtomicBoolean(false),
    val operatorStop: AtomicBoolean = AtomicBoolean(false),
) {
    /** True iff the portfolio gate is open AND no operator stop is in effect. */
    val effectiveActive: Boolean
        get() = gateActive.get() && !operatorStop.get()

    /** Tear down the underlying strategy session. */
    fun close() = handle.close()

    /** Close every open position on the underlying strategy at market. */
    fun flatten() = handle.live.flatten()
}
