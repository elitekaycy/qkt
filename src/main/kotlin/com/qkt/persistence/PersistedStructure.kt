package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.events.StructureOutcome
import com.qkt.strategy.StructureState
import java.math.BigDecimal

/**
 * On-disk shape of one leg of a live option structure: the contract and how it opened, what it holds
 * and realized, and the closing orders still working ([closing], quantity by order id).
 */
data class PersistedStructureLeg(
    val symbol: String,
    val side: Side,
    val openOrderId: String,
    val contractSize: BigDecimal,
    val expiryMs: Long,
    val opened: BigDecimal,
    val entryPrice: BigDecimal?,
    val held: BigDecimal,
    val realized: BigDecimal,
    val openEnded: Boolean,
    val closing: Map<String, BigDecimal>,
)

/** On-disk shape of one live option structure of a strategy, so a restart keeps it whole. */
data class PersistedStructure(
    val id: String,
    val alias: String,
    val size: BigDecimal,
    val state: StructureState,
    val exit: StructureOutcome,
    val legs: List<PersistedStructureLeg>,
)
