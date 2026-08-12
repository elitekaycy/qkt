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
        const val BRACKET_PAIRS_FILE = "bracket-pairs.json"
        const val PENDING_ORDERS_FILE = "pending-orders.json"
        const val PENDING_STACKS_FILE = "pending-stacks.json"
        const val OCO_LEGS_FILE = "oco-legs.json"
        const val TRAILING_STOPS_FILE = "trailing-stops.json"
        const val RISK_STATE_FILE = "risk-state.json"
        const val PNL_FILE = "pnl.json"
        const val TRADE_HISTORY_FILE = "trade-history.json"
        const val SEQUENCES_FILE = "sequences.json"
        const val EXIT_HOOKS_FILE = "exit-hooks.json"
        const val SCHEMA_VERSION = 1
    }

    override fun saveSequences(
        strategyId: String,
        states: Map<String, PersistedSequenceState>,
    ) {
        val dto =
            SequencesDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                sequences =
                    states.values.map { state ->
                        SequenceStateDto(
                            name = state.name,
                            stage = state.stage,
                            snapshots =
                                state.snapshots.map {
                                    SequenceSnapshotDto(
                                        stage = it.stage,
                                        price = it.price.toPlainString(),
                                        timeMs = it.timeMs,
                                    )
                                },
                            lastValues = state.lastValues,
                            completePulse = state.completePulse,
                        )
                    },
            )
        runCatching { json.encodeToString(SequencesDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, SEQUENCES_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveSequences encode for $strategyId", e) }
    }

    override fun saveExitHooks(
        strategyId: String,
        bindings: List<PersistedExitHookBinding>,
    ) {
        val dto =
            ExitHooksDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                bindings = bindings.map(ExitHookBindingDto::fromDomain),
            )
        runCatching { json.encodeToString(ExitHooksDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, EXIT_HOOKS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveExitHooks encode for $strategyId", e) }
    }

    override fun loadExitHooks(strategyId: String): List<PersistedExitHookBinding> {
        val raw = writer.read(strategyId, EXIT_HOOKS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(ExitHooksDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadExitHooks parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadExitHooks schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
        }
        require(dto.strategyId == strategyId) {
            "loadExitHooks strategy mismatch: file=${dto.strategyId}, requested=$strategyId"
        }
        return dto.bindings.map { it.toDomain() }
    }

    override fun loadSequences(strategyId: String): Map<String, PersistedSequenceState> {
        val raw = writer.read(strategyId, SEQUENCES_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(SequencesDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadSequences parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadSequences schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
        }
        return dto.sequences.associate { state ->
            state.name to
                PersistedSequenceState(
                    name = state.name,
                    stage = state.stage,
                    snapshots =
                        state.snapshots.map {
                            PersistedSequenceSnapshot(
                                stage = it.stage,
                                price = it.price.toBigDecimal(),
                                timeMs = it.timeMs,
                            )
                        },
                    lastValues = state.lastValues,
                    completePulse = state.completePulse,
                )
        }
    }

    override fun saveTradeHistory(
        strategyId: String,
        state: PersistedTradeHistory,
    ) {
        val dto =
            TradeHistoryDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                outcomes =
                    state.outcomes.map {
                        TradeOutcomeDto(
                            timestamp = it.timestamp,
                            pnl = it.pnl.toPlainString(),
                            symbol = it.symbol,
                        )
                    },
            )
        runCatching { json.encodeToString(TradeHistoryDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, TRADE_HISTORY_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveTradeHistory encode for $strategyId", e) }
    }

    override fun loadTradeHistory(strategyId: String): PersistedTradeHistory? {
        val raw = writer.read(strategyId, TRADE_HISTORY_FILE) ?: return null
        val dto =
            try {
                json.decodeFromString(TradeHistoryDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadTradeHistory parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadTradeHistory schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
        }
        return PersistedTradeHistory(
            outcomes =
                dto.outcomes.map {
                    PersistedTradeOutcome(
                        timestamp = it.timestamp,
                        pnl = it.pnl.toBigDecimal(),
                        symbol = it.symbol,
                    )
                },
        )
    }

    override fun savePnl(
        strategyId: String,
        state: PersistedPnl,
    ) {
        val dto =
            PnlDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                realized = state.realized.toPlainString(),
            )
        runCatching { json.encodeToString(PnlDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PNL_FILE, it) }
            .onFailure { e -> writer.recordFailure("savePnl encode for $strategyId", e) }
    }

    override fun loadPnl(strategyId: String): PersistedPnl? {
        val raw = writer.read(strategyId, PNL_FILE) ?: return null
        val dto =
            try {
                json.decodeFromString(PnlDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadPnl parse failed for $strategyId", e)
            }
        require(dto.version == SCHEMA_VERSION) {
            "loadPnl schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
        }
        return PersistedPnl(realized = dto.realized.toBigDecimal())
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
                globalRealizedTotal = state.globalRealizedTotal?.toPlainString(),
                dailyDrawdownEpochDay = state.dailyDrawdownEpochDay,
                globalDailyDrawdownRef = state.globalDailyDrawdownRef?.toPlainString(),
                perStrategyDailyDrawdownRefs =
                    state.perStrategyDailyDrawdownRefs.mapValues { it.value.toPlainString() },
                peakTotalEquity = state.peakTotalEquity?.toPlainString(),
                perStrategyPeakEquity = state.perStrategyPeakEquity.mapValues { it.value.toPlainString() },
                pacerEntryFillsByStrategy = state.pacerEntryFillsByStrategy,
                pacerLossStreakByStrategy = state.pacerLossStreakByStrategy,
                pacerLastLossAtByStrategy = state.pacerLastLossAtByStrategy,
            )
        runCatching { json.encodeToString(RiskStateDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, RISK_STATE_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveRiskState encode for $strategyId", e) }
    }

    override fun loadRiskState(strategyId: String): PersistedRiskState? {
        val raw = writer.read(strategyId, RISK_STATE_FILE) ?: return null
        val dto = json.decodeFromString(RiskStateDto.serializer(), raw)
        require(dto.version == SCHEMA_VERSION) {
            "loadRiskState schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION"
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
            globalRealizedTotal = dto.globalRealizedTotal?.toBigDecimal(),
            dailyDrawdownEpochDay = dto.dailyDrawdownEpochDay,
            globalDailyDrawdownRef = dto.globalDailyDrawdownRef?.toBigDecimal(),
            perStrategyDailyDrawdownRefs =
                dto.perStrategyDailyDrawdownRefs.mapValues { it.value.toBigDecimal() },
            peakTotalEquity = dto.peakTotalEquity?.toBigDecimal(),
            perStrategyPeakEquity = dto.perStrategyPeakEquity.mapValues { it.value.toBigDecimal() },
            pacerEntryFillsByStrategy = dto.pacerEntryFillsByStrategy,
            pacerLossStreakByStrategy = dto.pacerLossStreakByStrategy,
            pacerLastLossAtByStrategy = dto.pacerLastLossAtByStrategy,
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
            .onFailure { e -> writer.recordFailure("saveLegBook encode for $strategyId/$symbol", e) }
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
private data class StopStepDto(
    val mfeThreshold: String,
    val profitDistance: String,
)

@Serializable
private data class SequencesDto(
    val version: Int,
    val strategyId: String,
    val sequences: List<SequenceStateDto>,
)

@Serializable
private data class SequenceStateDto(
    val name: String,
    val stage: Int,
    val snapshots: List<SequenceSnapshotDto>,
    val lastValues: Map<String, Boolean> = emptyMap(),
    val completePulse: Boolean = false,
)

@Serializable
private data class SequenceSnapshotDto(
    val stage: String,
    val price: String,
    val timeMs: Long,
)

@Serializable
private data class ExitHooksDto(
    val version: Int,
    val strategyId: String,
    val bindings: List<ExitHookBindingDto>,
)

@Serializable
private data class ExitHookBindingDto(
    val bindingId: String,
    val strategyId: String,
    val symbol: String,
    val entrySide: String,
    val definitionId: String,
    val fingerprint: String,
    val entryOrderIds: List<String>,
    val stopOrderIds: List<String>,
    val takeProfitOrderIds: List<String>,
    val closeOrderIds: List<String> = emptyList(),
    val brokerTickets: List<String> = emptyList(),
    val activeQuantity: String,
    val exitQuantity: String,
    val exitPnl: String,
) {
    fun toDomain(): PersistedExitHookBinding =
        PersistedExitHookBinding(
            bindingId = bindingId,
            strategyId = strategyId,
            symbol = symbol,
            entrySide = Side.valueOf(entrySide),
            definitionId = definitionId,
            fingerprint = fingerprint,
            entryOrderIds = entryOrderIds,
            stopOrderIds = stopOrderIds,
            takeProfitOrderIds = takeProfitOrderIds,
            closeOrderIds = closeOrderIds,
            brokerTickets = brokerTickets,
            activeQuantity = BigDecimal(activeQuantity),
            exitQuantity = BigDecimal(exitQuantity),
            exitPnl = BigDecimal(exitPnl),
        )

    companion object {
        fun fromDomain(binding: PersistedExitHookBinding): ExitHookBindingDto =
            ExitHookBindingDto(
                bindingId = binding.bindingId,
                strategyId = binding.strategyId,
                symbol = binding.symbol,
                entrySide = binding.entrySide.name,
                definitionId = binding.definitionId,
                fingerprint = binding.fingerprint,
                entryOrderIds = binding.entryOrderIds,
                stopOrderIds = binding.stopOrderIds,
                takeProfitOrderIds = binding.takeProfitOrderIds,
                closeOrderIds = binding.closeOrderIds,
                brokerTickets = binding.brokerTickets,
                activeQuantity = binding.activeQuantity.toPlainString(),
                exitQuantity = binding.exitQuantity.toPlainString(),
                exitPnl = binding.exitPnl.toPlainString(),
            )
    }
}

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
    val closesTicket: String? = null,
    val closesLegId: String? = null,
    val partialClose: Boolean = false,
    val limitPrice: String? = null,
    val stopPrice: String? = null,
    val triggerPrice: String? = null,
    val onTrigger: String? = null,
    val expiresAt: Long? = null,
    val entryPrice: String? = null,
    val trailDistance: String? = null,
    val mfeThreshold: String? = null,
    val initialDistance: String? = null,
    val steps: List<StopStepDto>? = null,
    val tightenBy: String? = null,
    val intervalMs: Long? = null,
    val floorDistance: String? = null,
    val trailAmount: String? = null,
    val trailMode: String? = null,
    val limitOffset: String? = null,
    val entry: OrderRequestDto? = null,
    val parent: OrderRequestDto? = null,
    val children: List<OrderRequestDto>? = null,
    val takeProfit: String? = null,
    val stopLossType: String? = null,
    val takeProfitAst: ChildPriceAstDto? = null,
    val stopLossAst: ChildPriceAstDto? = null,
    val scaleOutLegs: List<ScaleOutLegDto>? = null,
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
                    closesTicket = closesTicket,
                    closesLegId = closesLegId,
                    partialClose = partialClose,
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
                    closesTicket = closesTicket,
                    partialClose = partialClose,
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
            "SteppedStop" ->
                com.qkt.execution.OrderRequest.SteppedStop(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    entryPrice =
                        java.math.BigDecimal(
                            requireNotNull(entryPrice) { "SteppedStop DTO missing entryPrice" },
                        ),
                    initialDistance =
                        java.math.BigDecimal(
                            requireNotNull(initialDistance) { "SteppedStop DTO missing initialDistance" },
                        ),
                    steps =
                        requireNotNull(steps) { "SteppedStop DTO missing steps" }.map {
                            com.qkt.execution.StopLossSpec.Step(
                                mfeThreshold = java.math.BigDecimal(it.mfeThreshold),
                                profitDistance = java.math.BigDecimal(it.profitDistance),
                            )
                        },
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "TimeTighteningStop" ->
                com.qkt.execution.OrderRequest.TimeTighteningStop(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    entryPrice =
                        java.math.BigDecimal(
                            requireNotNull(entryPrice) { "TimeTighteningStop DTO missing entryPrice" },
                        ),
                    initialDistance =
                        java.math.BigDecimal(
                            requireNotNull(initialDistance) { "TimeTighteningStop DTO missing initialDistance" },
                        ),
                    tightenBy =
                        java.math.BigDecimal(
                            requireNotNull(tightenBy) { "TimeTighteningStop DTO missing tightenBy" },
                        ),
                    intervalMs = requireNotNull(intervalMs) { "TimeTighteningStop DTO missing intervalMs" },
                    floorDistance =
                        java.math.BigDecimal(
                            requireNotNull(floorDistance) { "TimeTighteningStop DTO missing floorDistance" },
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
            "Bracket" -> {
                val stopLoss =
                    when (val stopType = requireNotNull(stopLossType) { "Bracket DTO missing stopLossType" }) {
                        "Fixed" ->
                            com.qkt.execution.StopLossSpec.Fixed(
                                java.math.BigDecimal(
                                    requireNotNull(stopPrice) { "Fixed bracket DTO missing stopPrice" },
                                ),
                            )
                        "ArmedTrail" ->
                            com.qkt.execution.StopLossSpec.ArmedTrail(
                                trailDistance =
                                    java.math.BigDecimal(
                                        requireNotNull(trailDistance) {
                                            "ArmedTrail bracket DTO missing trailDistance"
                                        },
                                    ),
                                mfeThreshold =
                                    java.math.BigDecimal(
                                        requireNotNull(mfeThreshold) {
                                            "ArmedTrail bracket DTO missing mfeThreshold"
                                        },
                                    ),
                            )
                        "SteppedStop" ->
                            com.qkt.execution.StopLossSpec.SteppedStop(
                                initialDistance =
                                    java.math.BigDecimal(
                                        requireNotNull(initialDistance) {
                                            "SteppedStop bracket DTO missing initialDistance"
                                        },
                                    ),
                                steps =
                                    requireNotNull(steps) { "SteppedStop bracket DTO missing steps" }.map {
                                        com.qkt.execution.StopLossSpec.Step(
                                            mfeThreshold = java.math.BigDecimal(it.mfeThreshold),
                                            profitDistance = java.math.BigDecimal(it.profitDistance),
                                        )
                                    },
                            )
                        "TimeTighten" ->
                            com.qkt.execution.StopLossSpec.TimeTighten(
                                initialDistance =
                                    java.math.BigDecimal(
                                        requireNotNull(initialDistance) {
                                            "TimeTighten bracket DTO missing initialDistance"
                                        },
                                    ),
                                tightenBy =
                                    java.math.BigDecimal(
                                        requireNotNull(tightenBy) {
                                            "TimeTighten bracket DTO missing tightenBy"
                                        },
                                    ),
                                intervalMs =
                                    requireNotNull(intervalMs) {
                                        "TimeTighten bracket DTO missing intervalMs"
                                    },
                                floorDistance =
                                    java.math.BigDecimal(
                                        requireNotNull(floorDistance) {
                                            "TimeTighten bracket DTO missing floorDistance"
                                        },
                                    ),
                            )
                        else -> error("Unknown bracket stop-loss type in persisted state: $stopType")
                    }
                com.qkt.execution.OrderRequest.Bracket(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    entry = requireNotNull(entry) { "Bracket DTO missing entry" }.toDomain(),
                    takeProfit =
                        java.math.BigDecimal(
                            requireNotNull(takeProfit) { "Bracket DTO missing takeProfit" },
                        ),
                    stopLoss = stopLoss,
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                    takeProfitAst = takeProfitAst?.toDomain(),
                    stopLossAst = stopLossAst?.toDomain(),
                )
            }
            "OTO" ->
                com.qkt.execution.OrderRequest.OTO(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    parent = requireNotNull(parent) { "OTO DTO missing parent" }.toDomain(),
                    children = requireNotNull(children) { "OTO DTO missing children" }.map { it.toDomain() },
                    timeInForce = tif,
                    timestamp = timestamp,
                    strategyId = strategyId,
                    expiresAt = expiresAt,
                )
            "ScaleOut" ->
                com.qkt.execution.OrderRequest.ScaleOut(
                    id = id,
                    symbol = symbol,
                    side = sideEnum,
                    quantity = qty,
                    basis = requireNotNull(entry) { "ScaleOut DTO missing basis" }.toDomain(),
                    legs =
                        requireNotNull(scaleOutLegs) { "ScaleOut DTO missing legs" }
                            .map { it.toDomain() },
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
                        closesTicket = req.closesTicket,
                        closesLegId = req.closesLegId,
                        partialClose = req.partialClose,
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
                        closesTicket = req.closesTicket,
                        partialClose = req.partialClose,
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
                is com.qkt.execution.OrderRequest.SteppedStop ->
                    OrderRequestDto(
                        type = "SteppedStop",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        entryPrice = req.entryPrice.toPlainString(),
                        initialDistance = req.initialDistance.toPlainString(),
                        steps =
                            req.steps.map {
                                StopStepDto(
                                    mfeThreshold = it.mfeThreshold.toPlainString(),
                                    profitDistance = it.profitDistance.toPlainString(),
                                )
                            },
                        expiresAt = req.expiresAt,
                    )
                is com.qkt.execution.OrderRequest.TimeTighteningStop ->
                    OrderRequestDto(
                        type = "TimeTighteningStop",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        entryPrice = req.entryPrice.toPlainString(),
                        initialDistance = req.initialDistance.toPlainString(),
                        tightenBy = req.tightenBy.toPlainString(),
                        intervalMs = req.intervalMs,
                        floorDistance = req.floorDistance.toPlainString(),
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
                is com.qkt.execution.OrderRequest.Bracket -> {
                    val stopFields =
                        when (val stop = req.stopLoss) {
                            is com.qkt.execution.StopLossSpec.Fixed ->
                                BracketStopFields(
                                    type = "Fixed",
                                    stopPrice = stop.price.toPlainString(),
                                )
                            is com.qkt.execution.StopLossSpec.ArmedTrail ->
                                BracketStopFields(
                                    type = "ArmedTrail",
                                    trailDistance = stop.trailDistance.toPlainString(),
                                    mfeThreshold = stop.mfeThreshold.toPlainString(),
                                )
                            is com.qkt.execution.StopLossSpec.SteppedStop ->
                                BracketStopFields(
                                    type = "SteppedStop",
                                    initialDistance = stop.initialDistance.toPlainString(),
                                    steps =
                                        stop.steps.map {
                                            StopStepDto(
                                                mfeThreshold = it.mfeThreshold.toPlainString(),
                                                profitDistance = it.profitDistance.toPlainString(),
                                            )
                                        },
                                )
                            is com.qkt.execution.StopLossSpec.TimeTighten ->
                                BracketStopFields(
                                    type = "TimeTighten",
                                    initialDistance = stop.initialDistance.toPlainString(),
                                    tightenBy = stop.tightenBy.toPlainString(),
                                    intervalMs = stop.intervalMs,
                                    floorDistance = stop.floorDistance.toPlainString(),
                                )
                        }
                    OrderRequestDto(
                        type = "Bracket",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        expiresAt = req.expiresAt,
                        entry =
                            requireNotNull(fromDomain(req.entry)) {
                                "Bracket entry ${req.entry::class.simpleName} cannot be persisted"
                            },
                        takeProfit = req.takeProfit.toPlainString(),
                        stopLossType = stopFields.type,
                        stopPrice = stopFields.stopPrice,
                        trailDistance = stopFields.trailDistance,
                        mfeThreshold = stopFields.mfeThreshold,
                        initialDistance = stopFields.initialDistance,
                        steps = stopFields.steps,
                        tightenBy = stopFields.tightenBy,
                        intervalMs = stopFields.intervalMs,
                        floorDistance = stopFields.floorDistance,
                        takeProfitAst = req.takeProfitAst?.let(ChildPriceAstDto::fromDomain),
                        stopLossAst = req.stopLossAst?.let(ChildPriceAstDto::fromDomain),
                    )
                }
                is com.qkt.execution.OrderRequest.OTO -> {
                    val parent =
                        requireNotNull(fromDomain(req.parent)) {
                            "OTO ${req.id} parent ${req.parent::class.simpleName} cannot be persisted"
                        }
                    val children =
                        req.children.map { child ->
                            requireNotNull(fromDomain(child)) {
                                "OTO ${req.id} child ${child.id} (${child::class.simpleName}) cannot be persisted"
                            }
                        }
                    OrderRequestDto(
                        type = "OTO",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        expiresAt = req.expiresAt,
                        parent = parent,
                        children = children,
                    )
                }
                is com.qkt.execution.OrderRequest.ScaleOut ->
                    OrderRequestDto(
                        type = "ScaleOut",
                        id = req.id,
                        symbol = req.symbol,
                        side = req.side.name,
                        quantity = req.quantity.toPlainString(),
                        timeInForce = req.timeInForce.name,
                        timestamp = req.timestamp,
                        strategyId = req.strategyId,
                        expiresAt = req.expiresAt,
                        entry =
                            requireNotNull(fromDomain(req.basis)) {
                                "ScaleOut ${req.id} basis ${req.basis::class.simpleName} cannot be persisted"
                            },
                        scaleOutLegs = req.legs.map(ScaleOutLegDto::fromDomain),
                    )
                // Composite variants are persisted by their dedicated paths (OCO legs,
                // bracket pairs, stack tiers), not as flat pending orders — skip them here.
                is com.qkt.execution.OrderRequest.StandaloneOCO,
                is com.qkt.execution.OrderRequest.TimeExit,
                is com.qkt.execution.OrderRequest.Stack,
                -> null
            }
    }
}

@Serializable
private data class ScaleOutLegDto(
    val priceTarget: String,
    val fraction: String,
) {
    fun toDomain(): com.qkt.execution.ScaleOutLeg =
        com.qkt.execution.ScaleOutLeg(
            priceTarget = java.math.BigDecimal(priceTarget),
            fraction = java.math.BigDecimal(fraction),
        )

    companion object {
        fun fromDomain(leg: com.qkt.execution.ScaleOutLeg): ScaleOutLegDto =
            ScaleOutLegDto(
                priceTarget = leg.priceTarget.toPlainString(),
                fraction = leg.fraction.toPlainString(),
            )
    }
}

private data class BracketStopFields(
    val type: String,
    val stopPrice: String? = null,
    val trailDistance: String? = null,
    val mfeThreshold: String? = null,
    val initialDistance: String? = null,
    val steps: List<StopStepDto>? = null,
    val tightenBy: String? = null,
    val intervalMs: Long? = null,
    val floorDistance: String? = null,
)

@Serializable
private data class ChildPriceAstDto(
    val type: String,
    val first: FillAnchorExprDto,
    val second: FillAnchorExprDto? = null,
) {
    fun toDomain(): com.qkt.dsl.ast.ChildPriceAst =
        when (type) {
            "At" ->
                com.qkt.dsl.ast
                    .ChildAt(first.toDomain())
            "By" ->
                com.qkt.dsl.ast
                    .ChildBy(first.toDomain())
            "Pct" ->
                com.qkt.dsl.ast
                    .ChildPct(first.toDomain())
            "Rr" ->
                com.qkt.dsl.ast
                    .ChildRr(first.toDomain())
            "ArmedTrail" ->
                com.qkt.dsl.ast.ChildArmedTrail(
                    trailDistance = first.toDomain(),
                    mfeThreshold =
                        requireNotNull(second) {
                            "ArmedTrail child-price DTO missing mfeThreshold"
                        }.toDomain(),
                )
            else -> error("Unknown bracket child-price type in persisted state: $type")
        }

    companion object {
        fun fromDomain(value: com.qkt.dsl.ast.ChildPriceAst): ChildPriceAstDto =
            when (value) {
                is com.qkt.dsl.ast.ChildAt ->
                    ChildPriceAstDto("At", FillAnchorExprDto.fromDomain(value.price))
                is com.qkt.dsl.ast.ChildBy ->
                    ChildPriceAstDto("By", FillAnchorExprDto.fromDomain(value.distance))
                is com.qkt.dsl.ast.ChildPct ->
                    ChildPriceAstDto("Pct", FillAnchorExprDto.fromDomain(value.percent))
                is com.qkt.dsl.ast.ChildRr ->
                    ChildPriceAstDto("Rr", FillAnchorExprDto.fromDomain(value.multiplier))
                is com.qkt.dsl.ast.ChildArmedTrail ->
                    ChildPriceAstDto(
                        "ArmedTrail",
                        FillAnchorExprDto.fromDomain(value.trailDistance),
                        FillAnchorExprDto.fromDomain(value.mfeThreshold),
                    )
            }
    }
}

@Serializable
private data class FillAnchorExprDto(
    val type: String,
    val value: String? = null,
    val op: String? = null,
    val lhs: FillAnchorExprDto? = null,
    val rhs: FillAnchorExprDto? = null,
) {
    fun toDomain(): com.qkt.dsl.ast.ExprAst =
        when (type) {
            "NumLit" ->
                com.qkt.dsl.ast.NumLit(
                    java.math.BigDecimal(requireNotNull(value) { "NumLit fill-anchor DTO missing value" }),
                )
            "Entry" -> com.qkt.dsl.ast.StackEntryRef
            "BinaryOp" ->
                com.qkt.dsl.ast.BinaryOp(
                    op =
                        com.qkt.dsl.ast.BinOp.valueOf(
                            requireNotNull(op) { "BinaryOp fill-anchor DTO missing op" },
                        ),
                    lhs = requireNotNull(lhs) { "BinaryOp fill-anchor DTO missing lhs" }.toDomain(),
                    rhs = requireNotNull(rhs) { "BinaryOp fill-anchor DTO missing rhs" }.toDomain(),
                )
            else -> error("Unknown fill-anchor expression type in persisted state: $type")
        }

    companion object {
        fun fromDomain(value: com.qkt.dsl.ast.ExprAst): FillAnchorExprDto =
            when (value) {
                is com.qkt.dsl.ast.NumLit ->
                    FillAnchorExprDto("NumLit", value = value.value.toPlainString())
                com.qkt.dsl.ast.StackEntryRef -> FillAnchorExprDto("Entry")
                is com.qkt.dsl.ast.BinaryOp ->
                    FillAnchorExprDto(
                        type = "BinaryOp",
                        op = value.op.name,
                        lhs = fromDomain(value.lhs),
                        rhs = fromDomain(value.rhs),
                    )
                else ->
                    error(
                        "Unsupported persisted fill-anchor expression ${value::class.simpleName}",
                    )
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

@Serializable
private data class PnlDto(
    val version: Int,
    val strategyId: String,
    val realized: String,
)

@Serializable
private data class TradeHistoryDto(
    val version: Int,
    val strategyId: String,
    val outcomes: List<TradeOutcomeDto>,
)

@Serializable
private data class TradeOutcomeDto(
    val timestamp: Long,
    val pnl: String,
    val symbol: String,
)

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
    val globalRealizedTotal: String? = null,
    val dailyDrawdownEpochDay: Long? = null,
    val globalDailyDrawdownRef: String? = null,
    val perStrategyDailyDrawdownRefs: Map<String, String> = emptyMap(),
    val peakTotalEquity: String? = null,
    val perStrategyPeakEquity: Map<String, String> = emptyMap(),
    val pacerEntryFillsByStrategy: Map<String, List<Long>> = emptyMap(),
    val pacerLossStreakByStrategy: Map<String, Int> = emptyMap(),
    val pacerLastLossAtByStrategy: Map<String, Long> = emptyMap(),
)

@Serializable
private data class StrategyHaltDto(
    val strategyId: String,
    val reason: String,
    val scope: String,
    val epochDay: Long,
)
