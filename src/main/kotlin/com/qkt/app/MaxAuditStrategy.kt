package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.RefreshTrigger
import com.qkt.common.TimeMark
import com.qkt.common.TimeRange
import com.qkt.indicators.range.PreviousDayHigh
import com.qkt.indicators.range.PreviousDayLow
import com.qkt.indicators.range.RangeAggregateIndicator
import com.qkt.marketdata.Tick
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.Duration
import org.slf4j.LoggerFactory

class MaxAuditStrategy(
    private val symbols: List<String>,
) : Strategy,
    Warmable {
    override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 60)

    private val log = LoggerFactory.getLogger(MaxAuditStrategy::class.java)

    private val pdh: MutableMap<String, PreviousDayHigh> = mutableMapOf()
    private val pdl: MutableMap<String, PreviousDayLow> = mutableMapOf()
    private val r6h: MutableMap<String, RangeAggregateIndicator<BigDecimal>> = mutableMapOf()
    private val lastPrice: MutableMap<String, BigDecimal> = mutableMapOf()
    private var tickCount: Long = 0L
    private var crossAssetEvents: Long = 0L

    override fun onTick(
        tick: Tick,
        ctx: SessionContext,
        emit: (Signal) -> Unit,
    ) {
        if (tick.symbol !in symbols) return
        tickCount++

        val hi =
            pdh.getOrPut(tick.symbol) {
                PreviousDayHigh(tick.symbol, ctx.calendar, ctx.source, ctx.clock)
            }
        val lo =
            pdl.getOrPut(tick.symbol) {
                PreviousDayLow(tick.symbol, ctx.calendar, ctx.source, ctx.clock)
            }
        val rolling =
            r6h.getOrPut(tick.symbol) {
                RangeAggregateIndicator(
                    symbol = tick.symbol,
                    window = TimeWindow.ONE_MINUTE,
                    rangeSpec = {
                        TimeRange.of(
                            from = TimeMark.RelativeToNow(Duration.ofHours(6).negated()),
                            to = TimeMark.Now,
                            clock = ctx.clock,
                            calendar = ctx.calendar,
                        )
                    },
                    reduce = { it.maxOfOrNull { c -> c.high } },
                    source = ctx.source,
                    clock = ctx.clock,
                    refreshOn = RefreshTrigger.EveryNTicks(100),
                )
            }

        runCatching { hi.update(tick) }
            .onFailure { log.warn("PDH update failed for {}: {}", tick.symbol, it.message) }
        runCatching { lo.update(tick) }
            .onFailure { log.warn("PDL update failed for {}: {}", tick.symbol, it.message) }
        runCatching { rolling.update(tick) }
            .onFailure { log.warn("R6h update failed for {}: {}", tick.symbol, it.message) }
        lastPrice[tick.symbol] = tick.price

        if (tickCount % 50L == 0L) printDiag()

        val breaking =
            symbols.count { sym ->
                val px = lastPrice[sym] ?: return@count false
                val high = pdh[sym]?.value() ?: return@count false
                px > high
            }
        if (breaking >= 2) {
            crossAssetEvents++
            log.info("CROSS-ASSET BREAKOUT #{} ({} symbols above PDH)", crossAssetEvents, breaking)
        }
    }

    fun printDiag() {
        log.info("=== diagnostic at tick #{} (cross-asset events: {}) ===", tickCount, crossAssetEvents)
        for (sym in symbols) {
            val price = lastPrice[sym]
            val high = pdh[sym]?.value()
            val low = pdl[sym]?.value()
            val r6 = r6h[sym]?.value()
            log.info("  {}  px={}  pdh={}  pdl={}  r6h={}", sym, price, high, low, r6)
        }
    }
}
