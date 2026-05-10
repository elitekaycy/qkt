package com.qkt.broker.mt5

import com.qkt.broker.OrderTypeCapability
import java.math.BigDecimal

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
    val capabilities: Set<OrderTypeCapability>
        get() = MT5Protocol.capabilities - capabilityRestrictions
}

data class SymbolPolicy(
    val suffix: String = "",
    val aliases: Map<String, String> = emptyMap(),
)

data class InstrumentSpec(
    val minVolume: BigDecimal,
    val volumeStep: BigDecimal,
    val pointSize: BigDecimal,
    val digits: Int,
    val tradeStopsLevelPoints: Int,
)
