package com.qkt.cli

import com.qkt.connectivity.ConnectorRegistry
import com.qkt.marketdata.MarketDataGateConfig
import java.math.BigDecimal

/**
 * What one strategy really runs on, for the `effectiveConfig` of its `strategy.started`: the resolved value
 * of each setting that bounds its risk, and whether that value was configured or is qkt's default.
 *
 * This is an allow-list by construction. Every value is read from one named, typed [Config] getter, so
 * it is the value `liveFactory` and the portfolio deployer hand the session, and no map of the config
 * (`brokers`, `notify`, `insights`, `tv`, `fetchers`, ...) is ever walked: a credential, a gateway URL or a
 * field added to the config later has no way in. The only strings are qkt's own enums, a currency that
 * has the shape of one, an installed connector's type, and an account's name, which is only stated when a
 * stream of the strategy already names it. Numbers can only be numbers.
 *
 * A limit that is off has no `value`: `risk.max_daily_loss: 0` states `{"source": "configured"}`, never 0.
 */
class EffectiveConfigFacts(
    private val cfg: Config,
    private val connectorTypes: Set<String> = ConnectorRegistry.discover().types.toSet(),
) {
    /** A strategy deployed alone (or a `qkt bot` session's identity), its drawdown measured on `starting_balance`. */
    fun standalone(
        symbols: List<String>,
        override: PerStrategyRisk?,
        kind: String = "standalone",
    ): Map<String, Any?> {
        val balance = cfg.startingBalance.takeIf { it.signum() > 0 }
        return shared(kind, symbols) + ("startingBalance" to field(balance, balance != null)) +
            limits("strategy", override)
    }

    /** A child of the portfolio [portfolioName]: its loss and drawdown halts watch the book, on the book's capital. */
    fun portfolioChild(
        symbols: List<String>,
        override: PerStrategyRisk?,
        portfolioName: String,
        portfolioCapital: BigDecimal?,
        weight: BigDecimal?,
        allocatedCapital: BigDecimal?,
        allocates: Boolean,
    ): Map<String, Any?> {
        val book = book(portfolioName, portfolioCapital, weight, allocatedCapital, allocates)
        return shared("portfolio_child", symbols) + ("book" to book) + limits("book", override)
    }

    private fun shared(
        kind: String,
        symbols: List<String>,
    ): Map<String, Any?> =
        linkedMapOf(
            "schema" to SCHEMA,
            "kind" to kind,
            "runtimeMode" to field(cfg.runtimeMode.name.lowercase(), "mode" in cfg.runtime),
            "accounts" to accounts(symbols),
            "currency" to
                field(cfg.accountCurrency.takeIf { CURRENCY.matches(it) }, !cfg.account["currency"].isNullOrBlank()),
        )

    private fun accounts(symbols: List<String>): List<Map<String, Any?>> {
        val configured = cfg.accountConfigs().associateBy { it.name.uppercase() }
        return symbols
            .map { it.substringBefore(':', "").uppercase() }
            .distinct()
            .mapNotNull { prefix -> configured[prefix]?.let { prefix to it.type } }
            .map { (name, type) ->
                linkedMapOf("name" to name, "type" to type.takeIf { it in connectorTypes }).filterValues { it != null }
            }
    }

    private fun book(
        name: String,
        portfolioCapital: BigDecimal?,
        weight: BigDecimal?,
        allocatedCapital: BigDecimal?,
        allocates: Boolean,
    ): Map<String, Any?> {
        val book = cfg.bookRisk
        val limits = book?.limits
        val configured = limits != null
        val allocation = book?.allocation
        return linkedMapOf(
            "name" to name,
            "capital" to field(book?.capital ?: portfolioCapital, book?.capital != null || portfolioCapital != null),
            "weight" to weight,
            "allocatedCapital" to allocatedCapital,
            "maxGrossExposure" to field(limits?.maxGrossExposure, configured),
            "maxNetExposure" to field(limits?.maxNetExposure, configured),
            "maxSymbolConcentration" to field(limits?.maxSymbolConcentration, configured),
            "allocationMethod" to
                field(
                    if (allocates) "REGIME_WEIGHTED" else allocation?.method?.name,
                    allocates || allocation != null,
                ),
            "targetVol" to allocation?.targetVol.takeUnless { allocates },
            "deRiskSteps" to
                book?.deRisk?.ladder?.map {
                    linkedMapOf("drawdown" to it.drawdown, "factor" to it.factor, "cooldownBars" to it.cooldownBars)
                        .filterValues { value -> value != null }
                },
        )
    }

    private fun limits(
        appliesTo: String,
        override: PerStrategyRisk?,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>("risk" to risk(appliesTo)) +
            listOfNotNull(override?.let { "strategyRisk" to strategyRisk(it) }) +
            linkedMapOf("orders" to orders(), "marketData" to marketData())

    private fun risk(appliesTo: String): Map<String, Any?> =
        linkedMapOf(
            "appliesTo" to appliesTo,
            "maxDailyLoss" to riskField(cfg.maxDailyLoss.takeIf { it.signum() > 0 }, "max_daily_loss"),
            "maxDrawdownFraction" to riskField(cfg.maxDrawdownPct, "max_drawdown_pct"),
            "totalDdBasis" to riskField(cfg.totalDdBasis.name, "total_dd_basis"),
            "maxDailyDrawdownFraction" to riskField(cfg.maxDailyDrawdownPct, "max_daily_drawdown_pct"),
            "dailyDdBasis" to riskField(cfg.dailyDdBasis.name, "daily_dd_basis"),
            "liveEquityBasis" to riskField(cfg.liveEquityBasis.name, "live_equity_basis"),
        )

    private fun strategyRisk(o: PerStrategyRisk): Map<String, Any?> =
        linkedMapOf(
            "maxDailyLoss" to o.maxDailyLoss,
            "maxPositionSize" to o.maxPositionSize,
            "maxOpenPositions" to o.maxOpenPositions,
            "maxDrawdownFraction" to o.maxDrawdownPct,
            "maxDailyDrawdownFraction" to o.maxDailyDrawdownPct,
            "maxTradesPerDay" to o.maxTradesPerDay,
            "cooldownAfterLossMs" to o.cooldownAfterLossMs,
            "cooldownAfterLosses" to o.cooldownAfterLossAfterConsecutive.takeIf { o.cooldownAfterLossMs != null },
            "lossStreakHalt" to o.lossStreakHalt,
            "lossStreakHaltScope" to o.lossStreakHaltScope.name.takeIf { o.lossStreakHalt != null },
        ).filterValues { it != null }.mapValues { (_, value) -> field(value, true) }

    private fun orders(): Map<String, Any?> {
        val measured = cfg.measuredUsageHours > 0L
        return linkedMapOf(
            "maxOrderQty" to riskField(cfg.maxOrderQty, "max_order_qty"),
            "maxOrderNotional" to riskField(cfg.maxOrderNotional, "max_order_notional"),
            "priceCollarFraction" to riskField(cfg.priceCollarFrac, "price_collar_pct"),
            "marginFloorPct" to riskField(cfg.marginFloorPct.takeIf { it.signum() > 0 }, "margin_floor_pct"),
            "measuredUsageHours" to riskField(cfg.measuredUsageHours.takeIf { measured }, "measured_usage_hours"),
            "measuredUsageMaxQty" to riskField(cfg.measuredUsageMaxQty.takeIf { measured }, "measured_usage_max_qty"),
            "runawayMaxRoundTrips10m" to riskField(cfg.runawayMaxRoundTrips.takeIf { it > 0 }, "max_round_trips_10m"),
            "runawayMaxBrokerRejections1m" to
                riskField(cfg.runawayMaxRejections.takeIf { it > 0 }, "max_broker_rejections_1m"),
        )
    }

    private fun marketData(): Map<String, Any?> {
        val gate = cfg.marketData
        val shipped = MarketDataGateConfig.DEFAULT
        return linkedMapOf(
            "candleCloseGraceMs" to field(cfg.candleCloseGraceMs, "candle_close_grace_ms" in cfg.runtime),
            "staleAgeMultiple" to field(gate.staleAgeMultiple, gate.staleAgeMultiple != shipped.staleAgeMultiple),
            "minStaleAgeMs" to field(gate.minStaleAgeMs, gate.minStaleAgeMs != shipped.minStaleAgeMs),
            "outlierSigma" to field(gate.outlierSigma, gate.outlierSigma != shipped.outlierSigma),
            "maxClockSkewMs" to field(gate.maxClockSkewMs, gate.maxClockSkewMs != shipped.maxClockSkewMs),
            "quietAlertAfterMs" to field(gate.quietAlertAfterMs, gate.quietAlertAfterMs != shipped.quietAlertAfterMs),
        )
    }

    private fun riskField(
        value: Any?,
        key: String,
    ) = field(value, key in cfg.risk)

    private fun field(
        value: Any?,
        configured: Boolean,
    ): Map<String, Any?> = linkedMapOf("value" to value, "source" to if (configured) "configured" else "default")

    private companion object {
        const val SCHEMA = 1
        val CURRENCY = Regex("[A-Z]{3,5}")
    }
}

/** `effectiveConfig` built by [build], or nothing: stating a config never stops a strategy from starting. */
internal fun EffectiveConfigFacts?.metadata(build: EffectiveConfigFacts.() -> Map<String, Any?>): Map<String, Any?> =
    if (this == null) {
        emptyMap()
    } else {
        runCatching { mapOf("effectiveConfig" to build()) }.getOrDefault(emptyMap())
    }
