package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.persistence.orderrequest.OrderRequestDto
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * On-disk [StatePersistor]. Serializes engine state to atomic JSON files under
 * `<rootDir>/<strategyId>/`.
 *
 * Writes log and count failures; [com.qkt.app.LiveSession] turns a non-zero failure count into
 * an entry-only risk halt. Existing state that cannot be read, parsed, or validated fails startup
 * so a live session cannot silently reset durable risk or execution state.
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

    override fun healthSnapshot(): PersistenceHealth =
        PersistenceHealth(
            enabled = true,
            totalWrites = totalWrites,
            slowWrites = slowWrites,
            failedWrites = failedWrites,
            consecutiveFailures = writer.consecutiveFailures.get(),
            failureEpisodes = writer.failureEpisodes.get(),
        )

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    private companion object {
        const val LEGBOOK_FILE = "legbook.json"
        const val EXCURSION_FILE = "excursion.json"
        const val BRACKET_PAIRS_FILE = "bracket-pairs.json"
        const val PENDING_ORDERS_FILE = "pending-orders.json"
        const val PENDING_STACKS_FILE = "pending-stacks.json"
        const val OCO_LEGS_FILE = "oco-legs.json"
        const val TRAILING_STOPS_FILE = "trailing-stops.json"
        const val SCHEMA_VERSION = STATE_SCHEMA_VERSION
    }

    private val sequences = SequencesFile(writer, json)
    private val exitHooks = ExitHooksFile(writer, json)
    private val tradeHistory = TradeHistoryFile(writer, json)
    private val pnl = PnlFile(writer, json)
    private val riskState = RiskStateFile(writer, json)

    override fun saveSequences(
        strategyId: String,
        states: Map<String, PersistedSequenceState>,
    ) = sequences.save(strategyId, states)

    override fun saveExitHooks(
        strategyId: String,
        bindings: List<PersistedExitHookBinding>,
    ) = exitHooks.save(strategyId, bindings)

    override fun loadExitHooks(strategyId: String): List<PersistedExitHookBinding> = exitHooks.load(strategyId)

    override fun loadSequences(strategyId: String): Map<String, PersistedSequenceState> = sequences.load(strategyId)

    override fun saveTradeHistory(
        strategyId: String,
        state: PersistedTradeHistory,
    ) = tradeHistory.save(strategyId, state)

    override fun loadTradeHistory(strategyId: String): PersistedTradeHistory? = tradeHistory.load(strategyId)

    override fun savePnl(
        strategyId: String,
        state: PersistedPnl,
    ) = pnl.save(strategyId, state)

    override fun loadPnl(strategyId: String): PersistedPnl? = pnl.load(strategyId)

    override fun saveRiskState(
        strategyId: String,
        state: PersistedRiskState,
    ) = riskState.save(strategyId, state)

    override fun loadRiskState(strategyId: String): PersistedRiskState? = riskState.load(strategyId)

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
            .onFailure { e -> writer.recordFailure("saveLegBook encode for $strategyId/$symbol", e) }
    }

    override fun saveExcursion(
        strategyId: String,
        symbol: String,
        excursion: PersistedExcursion,
    ) {
        val dto =
            ExcursionDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                symbol = symbol,
                legId = excursion.legId,
                side = excursion.side.name,
                entryPrice = excursion.entryPrice.toPlainString(),
                mfe = excursion.mfe.toPlainString(),
                mae = excursion.mae.toPlainString(),
                adverseExtremePrice = excursion.adverseExtremePrice?.toPlainString(),
            )
        runCatching { json.encodeToString(ExcursionDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, fileNameFor(symbol, EXCURSION_FILE), it) }
            .onFailure { e -> writer.recordFailure("saveExcursion encode for $strategyId/$symbol", e) }
    }

    override fun loadExcursion(
        strategyId: String,
        symbol: String,
    ): PersistedExcursion? {
        val raw = writer.read(strategyId, fileNameFor(symbol, EXCURSION_FILE)) ?: return null
        // Excursion marks are a convenience, not a position of record: an unreadable file restores
        // as "no marks" rather than failing the deploy.
        val dto =
            try {
                json.decodeFromString(ExcursionDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadExcursion parse failed for {}/{}: {}", strategyId, symbol, e.message)
                return null
            }
        if (dto.version != SCHEMA_VERSION) {
            log.warn(
                "loadExcursion schema mismatch for {}/{}: {} != {}",
                strategyId,
                symbol,
                dto.version,
                SCHEMA_VERSION,
            )
            return null
        }
        return PersistedExcursion(
            legId = dto.legId,
            side = Side.valueOf(dto.side),
            entryPrice = BigDecimal(dto.entryPrice),
            mfe = BigDecimal(dto.mfe),
            mae = BigDecimal(dto.mae),
            adverseExtremePrice = dto.adverseExtremePrice?.let(::BigDecimal),
        )
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
                throw IllegalStateException("loadLegBook parse failed for $strategyId/$symbol", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadLegBook schema mismatch for $strategyId/$symbol: ${dto.version} != $SCHEMA_VERSION"
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
            .onFailure { e -> writer.recordFailure("saveBracketPairs encode for $strategyId", e) }
    }

    override fun loadBracketPairs(strategyId: String): List<BracketPair> {
        val raw = writer.read(strategyId, BRACKET_PAIRS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(BracketPairsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadBracketPairs parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadBracketPairs schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
        }
        return dto.pairs.map { it.toDomain() }
    }

    override fun savePendingOrders(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) {
        // Composite shapes with dedicated recovery channels are filtered upstream by
        // [com.qkt.app.OrderManager]. Pre-fill Brackets and OTO wrappers are retained here
        // because their entry-to-child arming state must survive a restart.
        val entries = orders.mapNotNull { (cid, req) -> OrderRequestDto.fromDomain(req)?.let { cid to it } }
        val dto =
            PendingOrdersDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                orders = entries.map { (cid, req) -> PendingOrderEntryDto(clientOrderId = cid, request = req) },
            )
        runCatching { json.encodeToString(PendingOrdersDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PENDING_ORDERS_FILE, it) }
            .onFailure { e -> writer.recordFailure("savePendingOrders encode for $strategyId", e) }
    }

    override fun savePendingOrdersSync(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) {
        val failuresBefore = failedWrites
        savePendingOrders(strategyId, orders)
        check(failedWrites == failuresBefore) {
            "durable pending-order intent write failed for $strategyId"
        }
    }

    override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> {
        val raw = writer.read(strategyId, PENDING_ORDERS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(PendingOrdersDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadPendingOrders parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadPendingOrders schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
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
                            openedAtMs = state.openedAtMs,
                        )
                    },
            )
        runCatching { json.encodeToString(PendingStacksDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PENDING_STACKS_FILE, it) }
            .onFailure { e -> writer.recordFailure("savePendingStacks encode for $strategyId", e) }
    }

    override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> {
        val raw = writer.read(strategyId, PENDING_STACKS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(PendingStacksDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadPendingStacks parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadPendingStacks schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
        }
        return dto.perPrimary.associate { entry ->
            entry.primaryLegId to
                PersistedTierState(
                    primaryClientOrderId = entry.primaryClientOrderId,
                    tiers = entry.tiers.map { it.toDomain() },
                    openedAtMs = entry.openedAtMs,
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
            .onFailure { e -> writer.recordFailure("saveOcoLegs encode for $strategyId", e) }
    }

    override fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg> {
        val raw = writer.read(strategyId, OCO_LEGS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(OcoLegsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadOcoLegs parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadOcoLegs schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
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

    override fun saveTrailingStops(
        strategyId: String,
        stops: List<PersistedTrailingStop>,
    ) {
        val entries =
            stops.mapNotNull { stop ->
                val req = OrderRequestDto.fromDomain(stop.request)
                if (req == null) {
                    log.warn(
                        "saveTrailingStops: skipping non-persistable variant ${stop.request::class.simpleName} " +
                            "for $strategyId/${stop.clientOrderId}",
                    )
                    null
                } else {
                    TrailingStopDto(
                        clientOrderId = stop.clientOrderId,
                        brokerOrderId = stop.brokerOrderId,
                        strategyId = stop.strategyId,
                        request = req,
                        armed = stop.armed,
                        hwm = stop.hwm.toPlainString(),
                        stepIndex = stop.stepIndex,
                        elapsedIntervals = stop.elapsedIntervals,
                        stopLevel = stop.stopLevel?.toPlainString(),
                    )
                }
            }
        val dto = TrailingStopsDto(version = SCHEMA_VERSION, strategyId = strategyId, stops = entries)
        runCatching { json.encodeToString(TrailingStopsDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, TRAILING_STOPS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveTrailingStops encode for $strategyId", e) }
    }

    override fun loadTrailingStops(strategyId: String): List<PersistedTrailingStop> {
        val raw = writer.read(strategyId, TRAILING_STOPS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(TrailingStopsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadTrailingStops parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadTrailingStops schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
        }
        return dto.stops.map {
            PersistedTrailingStop(
                clientOrderId = it.clientOrderId,
                brokerOrderId = it.brokerOrderId,
                strategyId = it.strategyId,
                request = it.request.toDomain(),
                armed = it.armed,
                hwm = java.math.BigDecimal(it.hwm),
                stepIndex = it.stepIndex,
                elapsedIntervals = it.elapsedIntervals,
                stopLevel = it.stopLevel?.let { value -> java.math.BigDecimal(value) },
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
private data class ExcursionDto(
    val version: Int,
    val strategyId: String,
    val symbol: String,
    val legId: String,
    val side: String,
    val entryPrice: String,
    val mfe: String,
    val mae: String,
    val adverseExtremePrice: String? = null,
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
private data class TrailingStopsDto(
    val version: Int,
    val strategyId: String,
    val stops: List<TrailingStopDto>,
)

@Serializable
private data class TrailingStopDto(
    val clientOrderId: String,
    val brokerOrderId: String? = null,
    val strategyId: String,
    val request: OrderRequestDto,
    val armed: Boolean,
    val hwm: String,
    val stepIndex: Int = 0,
    val elapsedIntervals: Long = 0L,
    val stopLevel: String? = null,
)

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
    val openedAtMs: Long? = null,
)

@Serializable
private data class TierDto(
    val index: Int,
    val mfeThreshold: String,
    val withinMs: Long,
    val stackQuantity: String,
    val slDistance: String,
    val tpDistance: String,
    val maeRecoverDistance: String? = null,
    val armedAdverseExtreme: String? = null,
    val fired: Boolean,
    val firedAt: Long? = null,
    val firedLegId: String? = null,
    val abandoned: Boolean = false,
) {
    fun toDomain(): PersistedTier =
        PersistedTier(
            index = index,
            mfeThreshold = BigDecimal(mfeThreshold),
            withinMs = withinMs,
            stackQuantity = BigDecimal(stackQuantity),
            slDistance = BigDecimal(slDistance),
            tpDistance = BigDecimal(tpDistance),
            maeRecoverDistance = maeRecoverDistance?.let(::BigDecimal),
            armedAdverseExtreme = armedAdverseExtreme?.let(::BigDecimal),
            fired = fired,
            firedAt = firedAt,
            firedLegId = firedLegId,
            abandoned = abandoned,
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
                maeRecoverDistance = t.maeRecoverDistance?.toPlainString(),
                armedAdverseExtreme = t.armedAdverseExtreme?.toPlainString(),
                fired = t.fired,
                firedAt = t.firedAt,
                firedLegId = t.firedLegId,
                abandoned = t.abandoned,
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
