package com.qkt.app

import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.ExitContext
import com.qkt.dsl.compile.ExitHookRef
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.strategy.Signal

/**
 * Fires exit hooks: holds each bound strategy's compiled runtime and emit path, runs a hook's
 * actions once its binding closes, and queues the actions of a deferred fill until
 * [dispatchReady] releases them for that stamped event. Owned by [ExitHookManager].
 */
internal class ExitHookDispatcher(
    private val bindings: ExitHookBindings,
) {
    private data class Runtime(
        val strategy: DslCompiledStrategy,
        val emit: (Signal) -> Unit,
    )

    private data class PendingDispatch(
        val runtime: Runtime,
        val ref: ExitHookRef,
        val context: ExitContext,
        val timestamp: Long,
    ) {
        fun execute() {
            runtime.strategy.executeExitHook(ref, context, timestamp).forEach(runtime.emit)
        }
    }

    private val runtimes = mutableMapOf<String, Runtime>()
    private val readyDispatches = mutableMapOf<Long, MutableList<PendingDispatch>>()

    fun bind(
        strategyId: String,
        strategy: DslCompiledStrategy,
        emit: (Signal) -> Unit,
    ) {
        runtimes[strategyId] = Runtime(strategy, emit)
    }

    /** The compiled strategy bound for [strategyId], or null when none is bound. */
    fun strategyFor(strategyId: String): DslCompiledStrategy? = runtimes[strategyId]?.strategy

    fun dispatchReady(event: BrokerEvent.OrderFilled) {
        readyDispatches.remove(event.sequenceId)?.forEach(PendingDispatch::execute)
    }

    /** Fire [binding]'s hook for [event]: now, or after [dispatchReady] when [defer] is set. */
    fun dispatch(
        binding: ExitHookBinding,
        event: BrokerEvent.OrderFilled,
        reason: ExitReason,
        defer: Boolean,
    ) {
        binding.fired = true
        val runtime = runtimes.getValue(binding.strategyId)
        val context =
            ExitContext(
                price = event.price,
                side = event.side,
                quantity = binding.exitQuantity,
                pnl = binding.exitPnl,
                reason = reason,
            )
        bindings.remove(binding.id)
        val pending = PendingDispatch(runtime, binding.ref, context, event.timestamp)
        if (defer) {
            readyDispatches.getOrPut(event.sequenceId) { mutableListOf() }.add(pending)
        } else {
            pending.execute()
        }
    }
}
