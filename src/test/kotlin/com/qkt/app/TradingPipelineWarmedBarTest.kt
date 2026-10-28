package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.HubKey
import com.qkt.engine.Engine
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.candleToTicks
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * #1525: warmup closed the 1m bars through minute N, then the venue's first live quote was its cached
 * snapshot stamped inside N. Live and replay must both keep warmup's bar N, on the bus and in the hub.
 */
class TradingPipelineWarmedBarTest {
    private val minute = 60_000L
    private val n = 29_886_953L * minute
    private val key = HubKey("PAPER", "BTC_USDC_PERPETUAL", "1m")
    private val prices = MarketPriceTracker()
    private val hub = CandleHub().apply { register(key, 5, "s") }
    private val candles = mutableListOf<Candle>()

    private fun pipeline(mode: Mode): TradingPipeline {
        val clock = FixedClock(time = n + minute + 27_445L)
        val sequencer = MonotonicSequenceGenerator()
        val strategyPositions = StrategyPositionTracker()
        val pnl = PnLCalculator(strategyPositions.account, prices)
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val bus = EventBus(clock, sequencer)
        return TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = sequencer,
            priceTracker = prices,
            positions = strategyPositions.account,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = PaperBroker(bus, clock, prices),
            engine = Engine(bus, prices),
            strategies = emptyList(),
            riskEngine = RiskEngine(rules = emptyList(), positions = strategyPositions.account),
            riskState = RiskState(pnl, strategyPnL, clock, bus),
            mode = mode,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
            candleHub = hub,
            onCandle = { candles += it },
        )
    }

    private fun bar(
        start: Long,
        close: String,
    ) = Candle(
        key.qktSymbol,
        Money.of("81393"),
        Money.of("81444"),
        Money.of("81393"),
        Money.of(close),
        Money.of("15.5959"),
        start,
        start + minute,
    )

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["LIVE", "BACKTEST"])
    fun `a live quote older than the last warmed bar never changes that bar`(mode: Mode) {
        val pipeline = pipeline(mode)
        val warmed = listOf(bar(n - 2 * minute, "81389.4"), bar(n - minute, "81393"), bar(n, "81434.3"))
        hub.seed(key, warmed)
        warmed.flatMap(::candleToTicks).forEach { pipeline.ingestForWarmup(it, minute) }

        val stale =
            Tick(key.qktSymbol, Money.of("81440.69"), n + 37_476L, bid = Money.of("81434.9"), ask = Money.of("81435"))
        pipeline.ingest(stale)
        pipeline.ingest(Tick(key.qktSymbol, Money.of("81423.76"), n + minute + 27_479L))
        pipeline.ingest(Tick(key.qktSymbol, Money.of("81423.8"), n + 2 * minute + 1_000L))

        assertThat(candles.map { it.startTime }).containsExactly(n - 2 * minute, n - minute, n, n + minute)
        assertThat(candles[2].close).isEqualByComparingTo("81434.3")
        assertThat(candles[2].bid).isNull()
        assertThat(hub.history(key, 1)?.close).isEqualByComparingTo("81434.3")
        assertThat(hub.latest(key)?.open).isEqualByComparingTo("81423.76")
        assertThat(prices.lastPrice(key.qktSymbol)).isEqualByComparingTo("81423.8")
    }
}
