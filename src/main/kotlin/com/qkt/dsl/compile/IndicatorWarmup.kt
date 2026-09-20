package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit

/**
 * The indicator's true warmup, read from a registry-built instance — exact for
 * multi-window indicators where the max literal undercounts (MACD(12,26,9) is 34
 * bars, HIGHEST(N) is N+1). Null when the call shape doesn't match the spec; the
 * caller falls back to [numLitMax]. [tfMinutes] is the timeframe of the stream the call
 * reads; time-anchored indicators (pivots, sessions, seasonality) convert their day or hour
 * spans into bars of it.
 */
internal fun registryWarmupBars(
    call: IndicatorCall,
    tfMinutes: Long?,
): Int? {
    val spec =
        com.qkt.dsl.stdlib.IndicatorRegistry
            .spec(call.name) ?: return null
    val consts =
        call.args
            .drop(spec.seriesCount)
            .filterIsInstance<NumLit>()
            .map { it.value }
    if (consts.size != spec.arity - spec.seriesCount) return null
    val registryBars =
        runCatching {
            com.qkt.dsl.stdlib.IndicatorRegistry
                .create(call.name, consts)
                .warmupBars
        }.getOrNull()
    if (tfMinutes == null) return registryBars

    fun barsForMinutes(minutes: Long): Int =
        ((minutes + tfMinutes - 1) / tfMinutes + 1)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    val timeAware =
        when (call.name.uppercase()) {
            "PIVOT_P", "PIVOT_R1", "PIVOT_S1",
            "VWAP_SESSION", "VWAP_SESSION_STDEV",
            "SESSION_RANGE_HIGH", "SESSION_RANGE_LOW",
            "IB_DEFENDED_HIGH", "IB_DEFENDED_LOW",
            -> barsForMinutes(1_440L)
            "SESSION_MOMENTUM" -> consts.getOrNull(2)?.toLong()?.let { barsForMinutes(it * 1_440L) }
            "SEASONAL_RANGE", "SEASONAL_RANGE_STDEV" ->
                consts.firstOrNull()?.toLong()?.let { barsForMinutes(it * 1_440L) }
            "ANCHORED_RETURN" -> consts.firstOrNull()?.toLong()?.let(::barsForMinutes)
            "REOPEN_GAP", "REOPEN_GAP_ORIGIN", "REOPEN_GAP_FILL" ->
                consts.firstOrNull()?.toLong()?.let { barsForMinutes(it * 60L) }
            else -> null
        }
    return maxOf(registryBars ?: 0, timeAware ?: 0).takeIf { it > 0 }
}

internal fun numLitMax(call: IndicatorCall): Int? =
    call.args
        .drop(1)
        .filterIsInstance<NumLit>()
        .maxOfOrNull { it.value.toInt() }
