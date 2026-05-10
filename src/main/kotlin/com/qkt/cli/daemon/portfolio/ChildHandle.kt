package com.qkt.cli.daemon.portfolio

import com.qkt.cli.daemon.StrategyHandle
import java.util.concurrent.atomic.AtomicBoolean

class ChildHandle(
    val parent: String,
    val alias: String,
    val hold: Boolean,
    val handle: StrategyHandle,
    val gateActive: AtomicBoolean = AtomicBoolean(false),
    val operatorStop: AtomicBoolean = AtomicBoolean(false),
) {
    val effectiveActive: Boolean
        get() = gateActive.get() && !operatorStop.get()

    fun close() = handle.close()

    fun flatten() = handle.live.flatten()
}
