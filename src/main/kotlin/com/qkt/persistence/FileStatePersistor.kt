package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * On-disk [StatePersistor]. Serializes the four state shapes to atomic JSON files under
 * `<rootDir>/<strategyId>/{legbook.json,bracket-pairs.json,pending-orders.json,pending-stacks.json}`.
 *
 * Per Phase 29 spec, errors are best-effort: write failures log + skip; load failures
 * return null + log. The engine never crashes from a persistence failure.
 */
class FileStatePersistor(
    rootDir: Path,
) : StatePersistor {
    private val log = LoggerFactory.getLogger(FileStatePersistor::class.java)
    private val writer = StateFileWriter(rootDir)

    /** Cumulative count of save operations that hit disk. */
    val totalWrites: Long get() = writer.totalWrites.get()

    /** Cumulative count of save operations whose latency exceeded the slow-write threshold (default 100ms). */
    val slowWrites: Long get() = writer.slowWrites.get()

    /** Cumulative count of save operations that threw an IOException (disk full, permission denied, ...). */
    val failedWrites: Long get() = writer.failedWrites.get()

    /** Cumulative JSON bytes written across all save operations. */
    val totalBytesWritten: Long get() = writer.totalBytesWritten.get()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    private companion object {
        const val LEGBOOK_FILE = "legbook.json"
        const val BRACKET_PAIRS_FILE = "bracket-pairs.json"
        const val PENDING_ORDERS_FILE = "pending-orders.json"
        const val PENDING_STACKS_FILE = "pending-stacks.json"
        const val OCO_LEGS_FILE = "oco-legs.json"
        const val RISK_STATE_FILE = "risk-state.json"
        const val SCHEMA_VERSION = 1
    }

    override fun saveRiskState(
        strategyId: String,
        state: PersistedRiskState,
    ) {
        val dto =
            RiskStateDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                epochDay = state.epochDay,
                realizedToday = state.realizedToday.toPlainString(),
                perStrategyRealizedToday = state.perStrategyRealizedToday.mapValues { it.value.toPlainString() },
                halted = state.halted,
                haltReason = state.haltReason,
                haltScope = state.haltScope,
                haltEpochDay = state.haltEpochDay,
                strategyHalts =
                    state.strategyHalts.map {
                        StrategyHaltDto(it.strategyId, it.reason, it.scope, it.epochDay)
                    },
            )
        runCatching { json.encodeToString(RiskStateDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, RISK_STATE_FILE, it) }
            .onFailure { e -> log.warn("saveRiskState encode failed for $strategyId: ${e.message}") }
    }

    override fun loadRiskState(strategyId: String): PersistedRiskState? {
        val raw = writer.read(strategyId, RISK_STATE_FILE) ?: return null
        val dto =
            try {
                json.decodeFromString(RiskStateDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadRiskState parse failed for $strategyId: ${e.message}")
                return null
            }
        return PersistedRiskState(
            epochDay = dto.epochDay,
            realizedToday = dto.realizedToday.toBigDecimal(),
            perStrategyRealizedToday = dto.perStrategyRealizedToday.mapValues { it.value.toBigDecimal() },
            halted = dto.halted,
            haltReason = dto.haltReason,
            haltScope = dto.haltScope,
            haltEpochDay = dto.haltEpochDay,
            strategyHalts =
                dto.strategyHalts.map {
                    PersistedStrategyHalt(it.strategyId, it.reason, it.scope, it.epochDay)
                },
        )
    }

    override fun saveLegBook(
        strategyId: String,
        symbol: String,
        legBook: LegBook,
    ) {
        val dto =
            LegBookDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                symbol = symbol,
                legs = legBook.all().map { LegDto.fromDomain(it.let(PersistedLeg::fromPositionLeg)) },
            )
        runCatching { json.encodeToString(LegBookDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, fileNameFor(symbol, LEGBOOK_FILE), it) }
            .onFailure { e -> log.warn("saveLegBook encode failed for $strategyId/$symbol: ${e.message}") }
    }

    override fun loadLegBook(
        strategyId: String,
        symbol: String,
    ): PersistedLegBook? {
        val raw = writer.read(strategyId, fileNameFor(symbol, LEGBOOK_FILE)) ?: return null
        val dto =
            try {
                json.decodeFromString(LegBookDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadLegBook parse failed for $strategyId/$symbol: ${e.message}")
                return null
            }
        if (dto.version != SCHEMA_VERSION) {
            log.warn("loadLegBook schema mismatch for $strategyId/$symbol: ${dto.version} != $SCHEMA_VERSION")
            return null
        }
        return PersistedLegBook(
            strategyId = dto.strategyId,
            symbol = dto.symbol,
            legs = dto.legs.map { it.toDomain() },
        )
    }

    override fun saveBracketPairs(
        strategyId: String,
        pairs: List<BracketPair>,
    ) {
        val dto =
            BracketPairsDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                pairs = pairs.map { BracketPairDto.fromDomain(it) },
            )
        runCatching { json.encodeToString(BracketPairsDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, BRACKET_PAIRS_FILE, it) }
            .onFailure { e -> log.warn("saveBracketPairs encode failed for $strategyId: ${e.message}") }
    }

    override fun loadBracketPairs(strategyId: String): List<BracketPair> {
        val raw = writer.read(strategyId, BRACKET_PAIRS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(BracketPairsDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadBracketPairs parse failed for $strategyId: ${e.message}")
                return emptyList()
            }
        if (dto.version != SCHEMA_VERSION) {
            log.warn("loadBracketPairs schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION")
            return emptyList()
        }
        return dto.pairs.map { it.toDomain() }
    }

    override fun savePendingOrders(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) {
        // Composite shapes (OCO, Bracket, etc.) are filtered upstream by [com.qkt.app.OrderManager]
        // because their recovery flows through dedicated channels (oco-legs.json, bracket pairs).
        // If one still arrives here, [OrderRequestDto.fromDomain] returns null and the entry is
        // silently dropped — this path stays quiet so the operational log doesn't fill with
        // false positives during a healthy run.
        val entries = orders.mapNotNull { (cid, req) -> OrderRequestDto.fromDomain(req)?.let { cid to it } }
        val dto =
            PendingOrdersDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                orders = entries.map { (cid, req) -> PendingOrderEntryDto(clientOrderId = cid, request = req) },
            )
        runCatching { json.encodeToString(PendingOrdersDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PENDING_ORDERS_FILE, it) }
            .onFailure { e -> log.warn("savePendingOrders encode failed for $strategyId: ${e.message}") }
    }

    override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> {
        val raw = writer.read(strategyId, PENDING_ORDERS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(PendingOrdersDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadPendingOrders parse failed for $strategyId: ${e.message}")
                return emptyMap()
            }
        if (dto.version != SCHEMA_VERSION) {
            log.warn("loadPendingOrders schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION")
            return emptyMap()
        }
        return dto.orders.associate { it.clientOrderId to it.request.toDomain() }
    }

    override fun savePendingStacks(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    ) {
        val dto =
            PendingStacksDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                perPrimary =
                    perPrimary.map { (primaryLegId, state) ->
                        PrimaryTierStateDto(
                            primaryLegId = primaryLegId,
                            primaryClientOrderId = state.primaryClientOrderId,
                            tiers = state.tiers.map { TierDto.fromDomain(it) },
                        )
                    },
            )
        runCatching { json.encodeToString(PendingStacksDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PENDING_STACKS_FILE, it) }
            .onFailure { e -> log.warn("savePendingStacks encode failed for $strategyId: ${e.message}") }
    }

    override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> {
        val raw = writer.read(strategyId, PENDING_STACKS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(PendingStacksDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadPendingStacks parse failed for $strategyId: ${e.message}")
                return emptyMap()
            }
        if (dto.version != SCHEMA_VERSION) {
            log.warn("loadPendingStacks schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION")
            return emptyMap()
        }
        return dto.perPrimary.associate { entry ->
            entry.primaryLegId to
                PersistedTierState(
                    primaryClientOrderId = entry.primaryClientOrderId,
                    tiers = entry.tiers.map { it.toDomain() },
                )
        }
    }

    override fun saveOcoLegs(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    ) {
        val entries =
            legs.mapNotNull { leg ->
                val req = OrderRequestDto.fromDomain(leg.request)
                if (req == null) {
                    log.warn(
                        "saveOcoLegs: skipping non-persistable variant ${leg.request::class.simpleName} " +
                            "for $strategyId/${leg.clientOrderId}",
                    )
                    null
                } else {
                    OcoLegDto(
                        clientOrderId = leg.clientOrderId,
                        brokerOrderId = leg.brokerOrderId,
                        strategyId = leg.strategyId,
                        request = req,
                        siblingIds = leg.siblingIds,
                    )
                }
            }
        val dto = OcoLegsDto(version = SCHEMA_VERSION, strategyId = strategyId, legs = entries)
        runCatching { json.encodeToString(OcoLegsDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, OCO_LEGS_FILE, it) }
            .onFailure { e -> log.warn("saveOcoLegs encode failed for $strategyId: ${e.message}") }
    }

    override fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg> {
        val raw = writer.read(strategyId, OCO_LEGS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(OcoLegsDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadOcoLegs parse failed for $strategyId: ${e.message}")
                return emptyList()
            }
        if (dto.version != SCHEMA_VERSION) {
            log.warn("loadOcoLegs schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION")
            return emptyList()
        }
        return dto.legs.map {
            PersistedOcoLeg(
                clientOrderId = it.clientOrderId,
                brokerOrderId = it.brokerOrderId,
                strategyId = it.strategyId,
                request = it.request.toDomain(),
                siblingIds = it.siblingIds,
            )
        }
    }

    override fun clearStrategy(strategyId: String) {
        writer.deleteStrategy(strategyId)
    }

    private fun fileNameFor(
        symbol: String,
        base: String,
    ): String = "$symbol-$base"
}

@Serializable
private data class LegBookDto(
    val version: Int,
    val strategyId: String,
    val symbol: String,
    val legs: List<LegDto>,
)

@Serializable
private data class PendingOrdersDto(
    val version: Int,
    val strategyId: String,
    val orders: List<PendingOrderEntryDto>,
)

@Serializable
private data class PendingOrderEntryDto(
    val clientOrderId: String,
    val request: OrderRequestDto,
)

@Serializable
private data class OcoLegsDto(
    val version: Int,
    val strategyId: String,
    val legs: List<OcoLegDto>,
)

@Serializable
private data class OcoLegDto(
    val clientOrderId: String,
    val brokerOrderId: String,
    val strategyId: String,
    val request: OrderRequestDto,
    val siblingIds: List<String>,
)

@Serializable
private data class OrderRequestDto(
    val type: String,
    val id: String,
    val symbol: String,
    val side: String,
    val quantity: String,
    val timeInForce: String,
    val timestamp: Long,
    val strategyId: String = "",
    // Variant-specific fields:
    val limitPrice: String? = null,
    val stopPrice: String? = null,
    val triggerPrice: String? = null,
    val onTrigger: String? = null,
    val expiresAt: Long? = null,
    val entryPrice: String? = null,
    val trailDistance: String? = null,
    val mfeThreshold: String? = null,
    val trailAmount: String? = null,
    val trailMode: String? = null,
    val limitOffset: String? = null,
) {
    fun toDomain(): com.qkt.execution.OrderRequest {
        val sideEnum =
            com.qkt.common.Side
                .valueOf(side)
        val qty = java.math.BigDecimal(quantity)
        val tif =
            com.qkt.execution.TimeInForce
                .valueOf(timeInForce)
        return when (type) {
            "Market" ->
                com.qkt.execution.OrderRequest.Market(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                )
            "Limit" ->
                com.qkt.execution.OrderRequest.Limit(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    limitPrice = java.math.BigDecimal(requireNotNull(limitPrice) { "Limit DTO missing limitPrice" }),
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "Stop" ->
                com.qkt.execution.OrderRequest.Stop(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    stopPrice = java.math.BigDecimal(requireNotNull(stopPrice) { "Stop DTO missing stopPrice" }),
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "IfTouched" ->
                com.qkt.execution.OrderRequest.IfTouched(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    triggerPrice =
                        java.math.BigDecimal(
                            requireNotNull(triggerPrice) { "IfTouched DTO missing triggerPrice" },
                        ),
                    onTrigger =
                        com.qkt.execution.TriggerType.valueOf(
                            requireNotNull(onTrigger) { "IfTouched DTO missing onTrigger" },
                        ),
                    limitPrice = limitPrice?.let { java.math.BigDecimal(it) },
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "ArmedTrailingStop" ->
                com.qkt.execution.OrderRequest.ArmedTrailingStop(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    entryPrice =
                        java.math.BigDecimal(
                            requireNotNull(entryPrice) { "ArmedTrailingStop DTO missing entryPrice" },
                        ),
                    trailDistance =
                        java.math.BigDecimal(
                            requireNotNull(trailDistance) { "ArmedTrailingStop DTO missing trailDistance" },
                        ),
                    mfeThreshold =
                        java.math.BigDecimal(
                            requireNotNull(mfeThreshold) { "ArmedTrailingStop DTO missing mfeThreshold" },
                        ),
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "StopLimit" ->
                com.qkt.execution.OrderRequest.StopLimit(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    stopPrice =
                        java.math.BigDecimal(
                            requireNotNull(stopPrice) { "StopLimit DTO missing stopPrice" },
                        ),
                    limitPrice =
                        java.math.BigDecimal(
                            requireNotNull(limitPrice) { "StopLimit DTO missing limitPrice" },
                        ),
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "TrailingStop" ->
                com.qkt.execution.OrderRequest.TrailingStop(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    trailAmount =
                        java.math.BigDecimal(
                            requireNotNull(trailAmount) { "TrailingStop DTO missing trailAmount" },
                        ),
                    trailMode =
                        com.qkt.execution.TrailMode.valueOf(
                            requireNotNull(trailMode) { "TrailingStop DTO missing trailMode" },
                        ),
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "TrailingStopLimit" ->
                com.qkt.execution.OrderRequest.TrailingStopLimit(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    trailAmount =
                        java.math.BigDecimal(
                            requireNotNull(trailAmount) { "TrailingStopLimit DTO missing trailAmount" },
                        ),
                    trailMode =
                        com.qkt.execution.TrailMode.valueOf(
                            requireNotNull(trailMode) { "TrailingStopLimit DTO missing trailMode" },
                        ),
                    limitOffset =
                        java.math.BigDecimal(
                            requireNotNull(limitOffset) { "TrailingStopLimit DTO missing limitOffset" },
                        ),
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            else -> error("Unknown OrderRequest type in persisted state: $type")
        }
    }

    companion object {
        fun fromDomain(req: com.qkt.execution.OrderRequest): OrderRequestDto? =
            when (req) {
                is com.qkt.execution.OrderRequest.Market ->
                    OrderRequestDto(
                        type = "Market",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                    )
                is com.qkt.execution.OrderRequest.Limit ->
                    OrderRequestDto(
                        type = "Limit",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        limitPrice = req.limitPrice.toPlainString(),
                        expiresAt = req.expiresAt,
                    )
                is com.qkt.execution.OrderRequest.Stop ->
                    OrderRequestDto(
                        type = "Stop",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        stopPrice = req.stopPrice.toPlainString(),
                        expiresAt = req.expiresAt,
                    )
                is com.qkt.execution.OrderRequest.IfTouched ->
                    OrderRequestDto(
                        type = "IfTouched",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        triggerPrice = req.triggerPrice.toPlainString(),
                        onTrigger = req.onTrigger.name,
                        limitPrice = req.limitPrice?.toPlainString(),
                        expiresAt = req.expiresAt,
                    )
                is com.qkt.execution.OrderRequest.ArmedTrailingStop ->
                    OrderRequestDto(
                        type = "ArmedTrailingStop",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        entryPrice = req.entryPrice.toPlainString(),
                        trailDistance = req.trailDistance.toPlainString(),
                        mfeThreshold = req.mfeThreshold.toPlainString(),
                        expiresAt = req.expiresAt,
                    )
                is com.qkt.execution.OrderRequest.StopLimit ->
                    OrderRequestDto(
                        type = "StopLimit",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        stopPrice = req.stopPrice.toPlainString(),
                        limitPrice = req.limitPrice.toPlainString(),
                        expiresAt = req.expiresAt,
                    )
                is com.qkt.execution.OrderRequest.TrailingStop ->
                    OrderRequestDto(
                        type = "TrailingStop",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        trailAmount = req.trailAmount.toPlainString(),
                        trailMode = req.trailMode.name,
                        expiresAt = req.expiresAt,
                    )
                is com.qkt.execution.OrderRequest.TrailingStopLimit ->
                    OrderRequestDto(
                        type = "TrailingStopLimit",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        trailAmount = req.trailAmount.toPlainString(),
                        trailMode = req.trailMode.name,
                        limitOffset = req.limitOffset.toPlainString(),
                        expiresAt = req.expiresAt,
                    )
                // Composite variants are persisted by their dedicated paths (OCO legs,
                // bracket pairs, stack tiers), not as flat pending orders — skip them here.
                is com.qkt.execution.OrderRequest.StandaloneOCO,
                is com.qkt.execution.OrderRequest.OTO,
                is com.qkt.execution.OrderRequest.Bracket,
                is com.qkt.execution.OrderRequest.ScaleOut,
                is com.qkt.execution.OrderRequest.TimeExit,
                is com.qkt.execution.OrderRequest.Stack,
                -> null
            }
    }
}

@Serializable
private data class PendingStacksDto(
    val version: Int,
    val strategyId: String,
    val perPrimary: List<PrimaryTierStateDto>,
)

@Serializable
private data class PrimaryTierStateDto(
    val primaryLegId: String,
    val primaryClientOrderId: String,
    val tiers: List<TierDto>,
)

@Serializable
private data class TierDto(
    val index: Int,
    val mfeThreshold: String,
    val withinMs: Long,
    val stackQuantity: String,
    val slDistance: String,
    val tpDistance: String,
    val fired: Boolean,
    val firedAt: Long? = null,
    val firedLegId: String? = null,
) {
    fun toDomain(): PersistedTier =
        PersistedTier(
            index = index,
            mfeThreshold = BigDecimal(mfeThreshold),
            withinMs = withinMs,
            stackQuantity = BigDecimal(stackQuantity),
            slDistance = BigDecimal(slDistance),
            tpDistance = BigDecimal(tpDistance),
            fired = fired,
            firedAt = firedAt,
            firedLegId = firedLegId,
        )

    companion object {
        fun fromDomain(t: PersistedTier): TierDto =
            TierDto(
                index = t.index,
                mfeThreshold = t.mfeThreshold.toPlainString(),
                withinMs = t.withinMs,
                stackQuantity = t.stackQuantity.toPlainString(),
                slDistance = t.slDistance.toPlainString(),
                tpDistance = t.tpDistance.toPlainString(),
                fired = t.fired,
                firedAt = t.firedAt,
                firedLegId = t.firedLegId,
            )
    }
}

@Serializable
private data class BracketPairsDto(
    val version: Int,
    val strategyId: String,
    val pairs: List<BracketPairDto>,
)

@Serializable
private data class BracketPairDto(
    val entryClientOrderId: String,
    val stopLossClientOrderId: String? = null,
    val takeProfitClientOrderId: String? = null,
    val legId: String? = null,
) {
    fun toDomain(): BracketPair =
        BracketPair(
            entryClientOrderId = entryClientOrderId,
            stopLossClientOrderId = stopLossClientOrderId,
            takeProfitClientOrderId = takeProfitClientOrderId,
            legId = legId,
        )

    companion object {
        fun fromDomain(p: BracketPair): BracketPairDto =
            BracketPairDto(
                entryClientOrderId = p.entryClientOrderId,
                stopLossClientOrderId = p.stopLossClientOrderId,
                takeProfitClientOrderId = p.takeProfitClientOrderId,
                legId = p.legId,
            )
    }
}

@Serializable
private data class LegDto(
    val legId: String,
    val parentLegId: String? = null,
    val role: String,
    val side: String,
    val symbol: String,
    val quantity: String,
    val entryPrice: String,
    val openedAt: Long,
    val brokerTicket: String? = null,
) {
    fun toDomain(): PersistedLeg =
        PersistedLeg(
            legId = legId,
            parentLegId = parentLegId,
            role = LegRole.valueOf(role),
            side = Side.valueOf(side),
            symbol = symbol,
            quantity = BigDecimal(quantity),
            entryPrice = BigDecimal(entryPrice),
            openedAt = openedAt,
            brokerTicket = brokerTicket,
        )

    companion object {
        fun fromDomain(leg: PersistedLeg): LegDto =
            LegDto(
                legId = leg.legId,
                parentLegId = leg.parentLegId,
                role = leg.role.name,
                side = leg.side.name,
                symbol = leg.symbol,
                quantity = leg.quantity.toPlainString(),
                entryPrice = leg.entryPrice.toPlainString(),
                openedAt = leg.openedAt,
                brokerTicket = leg.brokerTicket,
            )
    }
}


@Serializable
private data class RiskStateDto(
    val version: Int,
    val strategyId: String,
    val epochDay: Long,
    val realizedToday: String,
    val perStrategyRealizedToday: Map<String, String>,
    val halted: Boolean,
    val haltReason: String?,
    val haltScope: String,
    val haltEpochDay: Long,
    val strategyHalts: List<StrategyHaltDto>,
)

@Serializable
private data class StrategyHaltDto(
    val strategyId: String,
    val reason: String,
    val scope: String,
    val epochDay: Long,
)
