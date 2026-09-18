package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Reads and writes `<symbol>-excursion.json`: the favorable and adverse excursion marks of the
 * open leg. Marks are a convenience, so an unreadable or mismatched file loads as no marks.
 */
internal class ExcursionFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    // Logs under FileStatePersistor so existing log filters keep matching after the split.
    private val log = LoggerFactory.getLogger(FileStatePersistor::class.java)

    fun save(
        strategyId: String,
        symbol: String,
        excursion: PersistedExcursion,
    ) {
        val dto =
            ExcursionDto(
                version = STATE_SCHEMA_VERSION,
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
            .onSuccess { writer.write(strategyId, symbolStateFileName(symbol, EXCURSION_FILE), it) }
            .onFailure { e -> writer.recordFailure("saveExcursion encode for $strategyId/$symbol", e) }
    }

    fun load(
        strategyId: String,
        symbol: String,
    ): PersistedExcursion? {
        val raw = writer.read(strategyId, symbolStateFileName(symbol, EXCURSION_FILE)) ?: return null
        // Excursion marks are a convenience, not a position of record: an unreadable file restores
        // as "no marks" rather than failing the deploy.
        val dto =
            try {
                json.decodeFromString(ExcursionDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadExcursion parse failed for {}/{}: {}", strategyId, symbol, e.message)
                return null
            }
        if (dto.version != STATE_SCHEMA_VERSION) {
            log.warn(
                "loadExcursion schema mismatch for {}/{}: {} != {}",
                strategyId,
                symbol,
                dto.version,
                STATE_SCHEMA_VERSION,
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
}

private const val EXCURSION_FILE = "excursion.json"

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
