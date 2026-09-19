package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.events.SignalEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.observability.LatencyRegistry
import com.qkt.observability.LatencyStage
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext

/**
 * The `emit` function one strategy is bound with. It applies the portfolio/operator gate, publishes
 * the [SignalEvent], and routes the signal: suppressions and pending-order cancels are handled
 * here, latches are armed, and order-bearing signals go to the shared [OrderSubmitter].
 */
internal class StrategySignalEmitter(
    private val strategyId: String,
    private val strategy: Strategy,
    private val ctx: StrategyContext,
    private val bus: EventBus,
    private val orderManager: OrderManager,
    private val latchManager: LatchManager,
    private val submitter: OrderSubmitter,
    private val gate: () -> Boolean,
    private val gateFor: (String) -> Boolean,
    private val latency: LatencyRegistry,
    private val latencyEnabled: Boolean,
) : (Signal) -> Unit {
    /** Emit [sig]: route it when forced or the gate is open, otherwise record and publish the drop. */
    override fun invoke(sig: Signal) {
        val force =
            (sig is Signal.Buy && sig.force) ||
                (sig is Signal.Sell && sig.force)
        if (force || (gate() && gateFor(strategyId))) {
            route(sig)
        } else {
            ctx.submissions.recordSuppressed()
            // No order exists yet, so no RiskRejectedEvent can fire — publish a
            // dedicated event or the drop is invisible to journals and operators.
            bus.publish(
                SignalSuppressedEvent(
                    signal = sig,
                    strategyId = strategyId,
                    reason = "portfolio gate inactive or operator stop",
                ),
            )
        }
    }

    private fun route(sig: Signal) {
        val t0 = if (latencyEnabled) System.nanoTime() else 0L
        bus.publish(SignalEvent(sig, strategyId = strategyId))
        if (sig is Signal.Suppressed) {
            ctx.submissions.recordSuppressed()
            bus.publish(
                SignalSuppressedEvent(
                    signal = sig,
                    strategyId = strategyId,
                    reason = sig.reason,
                ),
            )
        } else if (sig is Signal.CancelPendingForSymbol) {
            orderManager.cancelPendingForSymbol(sig.symbol)
            ctx.submissions.recordAccepted()
        } else if (sig is Signal.ArmLatch) {
            latchManager.arm(
                sig.compiled,
                sig.ec,
                emit = { request -> invoke(Signal.Submit(request)) },
            )
            ctx.submissions.recordAccepted()
        } else {
            submitter.submit(strategyId, strategy, ctx, sig)
        }
        if (latencyEnabled) {
            latency.observe(strategyId, LatencyStage.SIGNAL_TO_SUBMISSION, System.nanoTime() - t0)
        }
    }
}
