package com.qkt.app

import com.qkt.events.FillAccountedEvent
import com.qkt.positions.Position
import java.math.BigDecimal

/** [strategy]'s fill that took its holding of [symbol] from [before] to flat, as the pipeline accounts it. */
internal fun closingFill(
    strategy: String,
    symbol: String,
    before: String,
) = FillAccountedEvent(
    orderId = "c-$strategy",
    strategyId = strategy,
    symbol = symbol,
    fillSliceId = "c-$strategy:1",
    sourceFillSequenceId = 1L,
    cumulativeFilled = null,
    modeledCommissionAccount = BigDecimal.ZERO,
    venueCostsAccount = BigDecimal.ZERO,
    totalCostsAccount = BigDecimal.ZERO,
    accountNativeRealized = BigDecimal.ZERO,
    strategyNativeRealized = BigDecimal.ZERO,
    nativeCurrency = "USDC",
    grossAccountRealized = BigDecimal.ZERO,
    grossStrategyAccountRealized = BigDecimal.ZERO,
    accountCurrency = "USDC",
    netAccountRealized = BigDecimal.ZERO,
    netStrategyAccountRealized = BigDecimal.ZERO,
    conversionRate = null,
    conversionTimestampMs = null,
    conversionSource = null,
    contractSize = BigDecimal.ONE,
    accountPositionBefore = null,
    accountPositionAfter = null,
    strategyPositionBefore = Position(symbol, BigDecimal(before), BigDecimal("120")),
    strategyPositionAfter = null,
    reducedExposure = true,
    partial = false,
)
