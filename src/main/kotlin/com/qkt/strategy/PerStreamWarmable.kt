package com.qkt.strategy

/**
 * Per-stream warmup spec — sibling to [Warmable] for strategies that need
 * different warmup windows on different streams (e.g. 5m gold + 1h spx).
 *
 * The single-spec [Warmable] interface stays as a legacy fallback for non-DSL
 * strategies. Callers should prefer [PerStreamWarmable] when both are available.
 */
interface PerStreamWarmable {
    /** Map from qkt symbol (e.g. `"EXNESS:XAUUSD"`) to its required warmup spec. */
    val perStreamWarmup: Map<String, WarmupSpec>
}
