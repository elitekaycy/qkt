package com.qkt.trade.session

import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The door through which an external decision source's orders enter the trading
 * pipeline. One bridge per declared session identity: the session enqueues a
 * [Signal] via [submit] (from the HTTP thread), and the bridge emits it on the
 * next tick the engine processes — so the order flows through sizing, risk, and
 * the broker exactly like a deployed strategy's signal.
 *
 * e.g. client POSTs a buy intent → session calls `bridge.submit(Signal.Buy(...))`
 * → engine's next tick drains it → risk engine admits or rejects it.
 */
class BotBridgeStrategy : Strategy {
    private val pending = ConcurrentLinkedQueue<Signal>()

    /** Enqueues a signal for emission on the engine's next tick. Thread-safe. */
    fun submit(signal: Signal) {
        pending.add(signal)
    }

    override fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {
        if (pending.isEmpty()) return
        while (true) {
            val next = pending.poll() ?: return
            emit(next)
        }
    }
}
