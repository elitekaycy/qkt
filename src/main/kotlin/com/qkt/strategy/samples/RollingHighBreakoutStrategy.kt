package com.qkt.strategy.samples

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.RefreshTrigger
import com.qkt.common.TimeMark
import com.qkt.common.TimeRange
import com.qkt.indicators.range.RangeAggregateIndicator
import com.qkt.marketdata.Tick
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RollingHighBreakoutStrategy(
    private val symbol: String,
    private val lookback: Duration = Duration.ofDays(3),
    private val window: TimeWindow = TimeWindow.ONE_HOUR,
    private val size: BigDecimal = Money.of("1"),
) : Strategy,
    Warmable {
    init {
        require(!lookback.isZero && !lookback.isNegative) {
            "lookback must be positive: $lookback"
        }
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    override val warmup: WarmupSpec =
        WarmupSpec.Bars(window, count = (lookback.toMillis() / window.durationMs).toInt().coerceAtLeast(1))

    private var indicator: RangeAggregateIndicator<BigDecimal>? = null
    private var lastEmitDayEpoch: Long = Long.MIN_VALUE

    override fun onTick(
        tick: Tick,
        emit: (Signal) -> Unit,
    ) {
        // mode-aware path drives this strategy; default no-op when ctx is unavailable.
    }

    override fun onTickWithContext(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol != symbol) return
        val agg =
            indicator
                ?: RangeAggregateIndicator(
                    symbol = symbol,
                    window = window,
                    rangeSpec = {
                        TimeRange.of(
                            from = TimeMark.RelativeToNow(lookback.negated()),
                            to = TimeMark.Now,
                            clock = ctx.clock,
                            calendar = ctx.calendar,
                        )
                    },
                    reduce = { it.maxOfOrNull { c -> c.high } },
                    source = ctx.source,
                    clock = ctx.clock,
                    refreshOn = RefreshTrigger.OnSessionRollover,
                ).also { indicator = it }
        agg.update(tick)
        val level = agg.value() ?: return
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
