package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NoopNotifier
import com.qkt.observe.insights.SharedDealFetch
import com.qkt.persistence.NoopStatePersistor
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The stated config is the daemon's: each value equals what `liveFactory` hands the session. */
class EffectiveConfigWiringTest {
    private val facts = { cfg: Config -> EffectiveConfigFacts(cfg, connectorTypes = setOf("mt5", "gateway")) }

    private fun config(
        dir: Path,
        yaml: String,
    ): Config = Config.load(Files.writeString(dir.resolve("qkt.config.yaml"), yaml))

    private fun Map<String, Any?>.group(name: String): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST")
        (this[name] as Map<String, Any?>)

    private fun Map<String, Any?>.field(name: String): Map<String, Any?> = group(name)

    @Test
    fun `what is stated is what the daemon's factory hands the session`(
        @TempDir dir: Path,
    ) {
        val yaml =
            """
            starting_balance: "25000"
            account: { currency: usdc }
            runtime: { mode: paper, candle_close_grace_ms: 3500 }
            market_data: { outlier_sigma: 9.0, min_stale_age_ms: 15000 }
            risk:
              max_daily_loss: "750"
              max_order_qty: "2"
              max_order_notional: "90000"
              price_collar_pct: "4"
              margin_floor_pct: "150"
              measured_usage_hours: 12
              measured_usage_max_qty: "0.05"
              max_round_trips_10m: 9
              max_broker_rejections_1m: 8
              max_drawdown_pct: "6"
              max_daily_drawdown_pct: "3"
              total_dd_basis: trailing
              daily_dd_basis: equity
              live_equity_basis: modeled
            """.trimIndent()
        val cfg = config(dir, yaml)
        val factory = factory(cfg, dir)
        val out = facts(cfg).standalone(emptyList(), null)
        val risk = out.group("risk")
        val orders = out.group("orders")
        val market = out.group("marketData")

        assertThat(out.group("startingBalance")["value"]).isEqualTo(factory.startingBalance)
        assertThat(out.group("currency")["value"]).isEqualTo(factory.accountingConfig.accountCurrency.code)
        assertThat(out.group("runtimeMode")["value"]).isEqualTo("paper")
        assertThat(risk.field("maxDailyLoss")["value"]).isEqualTo(factory.maxDailyLoss)
        assertThat(risk.field("maxDrawdownFraction")["value"]).isEqualTo(factory.maxDrawdownPct)
        assertThat(risk.field("maxDailyDrawdownFraction")["value"]).isEqualTo(factory.maxDailyDrawdownPct)
        assertThat(risk.field("totalDdBasis")["value"]).isEqualTo(factory.totalDdBasis.name)
        assertThat(risk.field("dailyDdBasis")["value"]).isEqualTo(factory.dailyDdBasis.name)
        assertThat(risk.field("liveEquityBasis")["value"]).isEqualTo(factory.liveEquityBasis.name)
        assertThat(orders.field("maxOrderQty")["value"]).isEqualTo(factory.maxOrderQty)
        assertThat(orders.field("maxOrderNotional")["value"]).isEqualTo(factory.maxOrderNotional)
        assertThat(orders.field("priceCollarFraction")["value"]).isEqualTo(factory.priceCollarFrac)
        assertThat(orders.field("marginFloorPct")["value"]).isEqualTo(factory.marginFloorPct)
        assertThat(orders.field("measuredUsageHours")["value"]).isEqualTo(factory.measuredUsageHours)
        assertThat(orders.field("measuredUsageMaxQty")["value"]).isEqualTo(factory.measuredUsageMaxQty)
        assertThat(orders.field("runawayMaxRoundTrips10m")["value"]).isEqualTo(factory.runawayMaxRoundTrips)
        assertThat(orders.field("runawayMaxBrokerRejections1m")["value"]).isEqualTo(factory.runawayMaxRejections)
        assertThat(market.field("candleCloseGraceMs")).isEqualTo(
            mapOf(
                "value" to factory.candleCloseGraceMs,
                "source" to "configured",
            ),
        )
        assertThat(market.field("outlierSigma")).isEqualTo(
            mapOf(
                "value" to factory.marketDataGateConfig.outlierSigma,
                "source" to "configured",
            ),
        )
        assertThat(market.field("minStaleAgeMs")["value"]).isEqualTo(factory.marketDataGateConfig.minStaleAgeMs)
        assertThat(market.field("staleAgeMultiple")["source"]).isEqualTo("default")
        assertThat(factory.effectiveConfig).isNotNull()
    }

    private fun factory(
        cfg: Config,
        dir: Path,
    ) = liveFactory(
        cfg = cfg,
        stateDir = StateDir.resolve(dir.toString()),
        marketSourceProvider = { Idle },
        brokerFactories = emptyMap(),
        instrumentRegistry = null,
        calendarFor = { TradingCalendar.fxDefault() },
        persistor = NoopStatePersistor(),
        notifier = NoopNotifier,
        notifyEvents = emptySet(),
        insightsSink = null,
        insightsDeployedIds = { emptyList() },
        insightsSharedDeals = SharedDealFetch(),
    )

    private object Idle : MarketSource {
        override val name = "Idle"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next(): Tick? = null

                override fun close() = Unit
            }
    }
}
