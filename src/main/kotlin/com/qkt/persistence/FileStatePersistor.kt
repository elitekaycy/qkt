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
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    private companion object {
        const val LEGBOOK_FILE = "legbook.json"
        const val BRACKET_PAIRS_FILE = "bracket-pairs.json"
        const val SCHEMA_VERSION = 1
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
        // Implemented in Task 7.
    }

    override fun loadPendingOrders(strategyId: String): Map<String, OrderRequest> {
        // Implemented in Task 7.
        return emptyMap()
    }

    override fun savePendingStacks(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    ) {
        // Implemented in Task 8.
    }

    override fun loadPendingStacks(strategyId: String): Map<String, PersistedTierState> {
        // Implemented in Task 8.
        return emptyMap()
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
            )
    }
}
