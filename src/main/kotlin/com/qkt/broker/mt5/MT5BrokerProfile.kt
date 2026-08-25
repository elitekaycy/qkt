package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability
import java.math.BigDecimal

/**
 * Per-venue configuration for an [MT5Broker].
 *
 * [name] is the venue identifier strategies use to route (`EXNESS:EURUSD`); [magic]
 * tags every order so the daemon can identify its own positions on restart;
 * [symbolPolicy] handles suffix differences between brokers (Exness adds `m`, others
 * don't); [serverTimeZone] converts broker wall time to UTC with DST-aware rules.
 * [capabilityRestrictions] subtracts from [MT5Protocol.capabilities] to model
 * brokers that disable certain order types. [symbolCalendars] picks the trading-session
 * calendar per symbol, so one broker can offer FX, crypto, and index CFDs at once; it
 * defaults to all-FX, matching the historical single-calendar behaviour. Live quote
 * polling uses [tickPollIntervalMs], independently of the position and pending-order
 * reconciliation cadence in [pollIntervalMs].
 */
data class MT5BrokerProfile(
    val name: String,
    val gatewayUrl: String,
    val symbolPolicy: SymbolPolicy,
    val serverTimeZone: MT5ServerTimeZone = MT5ServerTimeZone.UTC,
    val magic: Int,
    val instrumentOverrides: Map<String, InstrumentSpec> = emptyMap(),
    /** Position and pending-order reconciliation cadence in milliseconds. */
    val pollIntervalMs: Long = 1000,
    val httpTimeoutMs: Long = 5000,
    val retryAttempts: Int = 3,
    val deviationPoints: Int = 20,
    val apiKey: String? = null,
    val capabilityRestrictions: Set<OrderTypeCapability> = emptySet(),
    val symbolCalendars: SymbolCalendars = SymbolCalendars.fxDefault(),
    val expectedAccountLogin: Long? = null,
    val expectedAccountServer: String? = null,
    val expectedTradeMode: MT5TradeMode? = null,
    val expectedAccountCurrency: String? = null,
    val expectedLeverage: Int? = null,
    /**
     * Expected venue margin mode (#1071): NETTING or HEDGING. Asserted against the
     * account's `marginMode` at connect so the engine's position model can never
     * silently diverge from the venue's; also the source of truth when a live config
     * is replayed in backtest.
     */
    val expectedMarginMode: com.qkt.broker.PositionAccountingMode? = null,
    /** Live quote polling cadence in milliseconds. */
    val tickPollIntervalMs: Long = 1000,
) {
    init {
        require(pollIntervalMs > 0L) { "MT5 reconciliation poll interval must be positive" }
        require(tickPollIntervalMs > 0L) { "MT5 tick poll interval must be positive" }
    }

    /** Effective capabilities for this profile after applying [capabilityRestrictions]. */
    val capabilities: Set<OrderTypeCapability>
        get() = MT5Protocol.capabilities - capabilityRestrictions

    /** True when startup must assert at least one configured account property. */
    val hasExpectedAccount: Boolean
        get() =
            expectedAccountLogin != null ||
                expectedAccountServer != null ||
                expectedTradeMode != null ||
                expectedAccountCurrency != null ||
                expectedLeverage != null ||
                expectedMarginMode != null
}

/**
 * How a qkt symbol is translated to the broker's MT5 symbol.
 *
 * [suffix] is appended to every symbol (Exness uses `m` → `EURUSD` becomes `EURUSDm`).
 * [aliases] override the suffix logic for specific symbols.
 */
data class SymbolPolicy(
    val suffix: String = "",
    val aliases: Map<String, String> = emptyMap(),
)

/** Per-instrument trading constraints reported by the venue. Used for size/price validation. */
data class InstrumentSpec(
    val minVolume: BigDecimal,
    val volumeStep: BigDecimal,
    val pointSize: BigDecimal,
    val digits: Int,
    val tradeStopsLevelPoints: Int,
    val maxVolume: BigDecimal? = null,
)
