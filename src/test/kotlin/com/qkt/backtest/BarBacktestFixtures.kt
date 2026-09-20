package com.qkt.backtest

import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.strategy.Strategy

/**
 * #214 — a bars-only venue (crypto) must backtest by synthesizing ticks from its OHLC bars.
 * Proves: (1) a source advertising only BARS produces trades; (2) when real ticks exist they
 * are preferred over bar-synthesis; (3) the synthesized intra-bar low fires a stop the close
 * alone never would (the pessimistic O->L->H->C ordering).
 */
internal object BarBacktestFixtures {
    fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    fun candle(
        o: String,
        h: String,
        l: String,
        c: String,
        start: Long,
    ): Candle =
        Candle(
            "BYBIT_SPOT:BTCUSDT",
            Money.of(o),
            Money.of(h),
            Money.of(l),
            Money.of(c),
            Money.of("1"),
            start,
            start + 60_000L,
        )
}
