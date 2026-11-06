package com.qkt.app

import com.qkt.events.FillAccountedEvent
import com.qkt.positions.Position
import java.math.BigDecimal

/** [strategy]'s fill that took its holding of [symbol] from [before] to flat, as the pipeline accounts it. */
internal fun closingFill(
    strategy: String,
    symbol: String,
    before: String,
) = movingFill(strategy, symbol, before, null).copy(accountPositionBefore = null)

/**
 * [strategy]'s fill, accounted at [atMs] (0: when published), that took its holding of [symbol] from [before] to
 * [after] (null: flat), its session's holding moving alike, as the pipeline accounts it.
 */
internal fun movingFill(
    strategy: String,
    symbol: String,
    before: String,
    after: String?,
    atMs: Long = 0L,
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
    accountPositionBefore = Position(symbol, BigDecimal(before), BigDecimal("120")),
    accountPositionAfter = after?.let { Position(symbol, BigDecimal(it), BigDecimal("120")) },
    strategyPositionBefore = Position(symbol, BigDecimal(before), BigDecimal("120")),
    strategyPositionAfter = after?.let { Position(symbol, BigDecimal(it), BigDecimal("120")) },
    reducedExposure = after == null,
    partial = false,
    executedAt = atMs,
)
