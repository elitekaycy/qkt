package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability
import java.math.BigDecimal

/**
 * Per-venue configuration for an [MT5Broker].
 *
 * [name] is the venue identifier strategies use to route (`EXNESS:EURUSD`); [magic]
 * tags every order so the daemon can identify its own positions on restart;
 * [symbolPolicy] handles suffix differences between brokers (Exness adds `m`, others
 * don't). [capabilityRestrictions] subtracts from [MT5Protocol.capabilities] to model
 * brokers that disable certain order types.
 */
data class MT5BrokerProfile(
    val name: String,
    val gatewayUrl: String,
    val symbolPolicy: SymbolPolicy,
    val serverTzOffsetHours: Int = 0,
    val magic: Int,
    val instrumentOverrides: Map<String, InstrumentSpec> = emptyMap(),
    val pollIntervalMs: Long = 1000,
    val httpTimeoutMs: Long = 5000,
    val retryAttempts: Int = 3,
    val deviationPoints: Int = 20,
    val capabilityRestrictions: Set<OrderTypeCapability> = emptySet(),
) {
    /** Effective capabilities for this profile after applying [capabilityRestrictions]. */
    val capabilities: Set<OrderTypeCapability>
        get() = MT5Protocol.capabilities - capabilityRestrictions
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
)
