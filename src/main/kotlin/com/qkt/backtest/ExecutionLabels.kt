package com.qkt.backtest

/** Evidence label for the order-placement delay: fixed latency, plus the send-lane spacing when set. */
internal fun ExecutionSimulationConfig.latencyLabel(): String {
    val latency = if (latencyMs == 0L) "zero" else "fixed:${latencyMs}ms"
    return if (orderSpacingMs == 0L) latency else "$latency lane:${orderSpacingMs}ms"
}

/** Evidence label for the configured slippage model. */
internal fun ExecutionSimulationConfig.slippageLabel(): String =
    when (slippage) {
        SlippageSpec.ZERO -> "zero"
        SlippageSpec.INSTRUMENT -> "instrument:slippagePoints"
        SlippageSpec.FIXED_POINTS -> "fixed-points:$slippagePoints"
        SlippageSpec.UNIFORM_RANDOM -> "uniform-random:0..$slippagePoints"
    }
