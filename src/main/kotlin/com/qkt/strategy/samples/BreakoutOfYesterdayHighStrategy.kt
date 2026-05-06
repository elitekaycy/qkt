package com.qkt.strategy.samples

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.indicators.range.PreviousDayHigh
import com.qkt.marketdata.Tick
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

class BreakoutOfYesterdayHighStrategy(
    private val symbol: String,
    private val size: BigDecimal = Money.of("1"),
) : Strategy,
    Warmable {
    init {
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1440)

    private val indicators: MutableMap<String, PreviousDayHigh> = mutableMapOf()
    private var lastEmitDayEpoch: Long = Long.MIN_VALUE

    override fun onTick(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol != symbol) return
        val indicator =
            indicators.getOrPut(symbol) {
                PreviousDayHigh(symbol, ctx.calendar, ctx.source, ctx.clock)
            }
        indicator.update(tick)
        val level = indicator.value() ?: return
        if (tick.price <= level) return
        val today =
            Instant
                .ofEpochMilli(ctx.clock.now())
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toEpochDay()
        if (today == lastEmitDayEpoch) return
        lastEmitDayEpoch = today
        emit(Signal.Buy(symbol, size))
    }
}
