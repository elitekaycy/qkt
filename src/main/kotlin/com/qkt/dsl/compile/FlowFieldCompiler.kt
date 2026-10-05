package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.SideVolumes
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.source.MarketSource
import java.util.concurrent.atomic.AtomicReference

/**
 * Compiles `<alias>.buy_volume[n]`, `sell_volume[n]`, `long_liq_volume[n]` and `short_liq_volume[n]`: the volume a
 * contract's tape printed in one bar of the stream's timeframe, `n` (at least 1) bars before the newest closed one,
 * as its data source knows it ([TradeFlow]). The bar closing itself is refused: live reads its prints from the
 * gateway after it closes, so at its close they may not all have arrived, and a backtest would see more than live.
 * `[n]` reads the series directly (not through `lag`), so it needs no warmup of its own. Undefined while the bar's
 * prints are not known; a source serving no flow is refused before the strategy starts.
 */
internal object FlowFieldCompiler {
    /** The fields this compiler owns. */
    val fields: Set<String> = DslVocabulary.flowFields.toSet()

    /** The series [field] sums: the tape for aggressor volume, liquidations for liquidated volume. */
    fun kind(field: String): FlowKind = if (field.endsWith("_liq_volume")) FlowKind.LIQUIDATIONS else FlowKind.TRADES

    /** A flow field read on the bar being closed: always refused, naming the lookback that is allowed. */
    fun refuseClosingBar(ref: StreamFieldRef): Nothing =
        error(
            "${ref.stream}.${ref.field} is the flow of the bar being closed, which is not known when it closes: " +
                "read ${ref.stream}.${ref.field}[1] (the bar before) or further back",
        )

    /** `<alias>.<flow field>[n]` (parsed as `lag(<alias>.<field>, n)`) compiled from the series; null for any other call. */
    fun lookback(call: IndicatorCall): CompiledExpr? {
        val ref = call.args.firstOrNull() as? StreamFieldRef
        if (!call.name.equals("LAG", ignoreCase = true) || ref == null || ref.field !in fields) return null
        val n = (call.args.getOrNull(1) as? NumLit)?.value?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.toInt()
        require(call.args.size == 2 && n != null && n >= 1) {
            "${ref.stream}.${ref.field} is read a whole number of bars back, at least [1]"
        }
        return compile(ref, n)
    }

    private class Resolved(
        val source: MarketSource,
        val flow: TradeFlow?,
        val windowMs: Long,
    )

    private fun compile(
        ref: StreamFieldRef,
        barsBack: Int,
    ): CompiledExpr {
        val kind = kind(ref.field)
        val resolved = AtomicReference<Resolved?>()
        return CompiledExpr { ctx ->
            val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
            val source = ctx.strategyContext.source
            val at =
                resolved.get()?.takeIf { it.source === source }
                    ?: Resolved(source, source.tradeFlowFor(key.qktSymbol), TimeWindow.parse(key.timeframe).durationMs)
                        .also(resolved::set)
            val newestClosedEnd = Math.floorDiv(ctx.nowMs(), at.windowMs) * at.windowMs
            val start = newestClosedEnd - (barsBack + 1) * at.windowMs
            val sums = at.flow?.window(key.qktSymbol, kind, at.windowMs, start)
            if (sums == null) Value.Undefined else Value.Num(pick(ref.field, sums))
        }
    }

    /** A liquidation that sold closed a long, one that bought a short. */
    private fun pick(
        field: String,
        sums: SideVolumes,
    ) = when (field) {
        "buy_volume", "short_liq_volume" -> sums.buy
        else -> sums.sell
    }
}
