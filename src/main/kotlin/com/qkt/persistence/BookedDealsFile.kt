package com.qkt.persistence

import com.qkt.broker.BookedDeals
import com.qkt.broker.BookedFill
import com.qkt.broker.VenueLeg
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads and writes `booked-deals.json`: what a session booked of its venue's deals, as a restart takes it back. */
internal class BookedDealsFile(
    private val writer: StateFileWriter,
) : BookedDealPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    override fun saveBookedDeals(
        ownerId: String,
        deals: BookedDeals,
    ) {
        runCatching {
            json.encodeToString(
                BookedDealsDto.serializer(),
                BookedDealsDto(
                    STATE_SCHEMA_VERSION,
                    deals.fills.map(BookedFillDto::of),
                    deals.settledCloses.map(SettledCloseDto::of),
                ),
            )
        }.onSuccess { writer.write(ownerId, BOOKED_DEALS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveBookedDeals encode for $ownerId", e) }
    }

    override fun loadBookedDeals(ownerId: String): BookedDeals? {
        val raw = writer.read(ownerId, BOOKED_DEALS_FILE) ?: return null
        val dto = json.decodeFromString(BookedDealsDto.serializer(), raw)
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadBookedDeals schema mismatch for $ownerId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return BookedDeals(dto.fills.map { it.toDomain() }, dto.settledCloses.map { it.toDomain() })
    }

    @Serializable
    private data class BookedDealsDto(
        val version: Int,
        val fills: List<BookedFillDto> = emptyList(),
        val settledCloses: List<SettledCloseDto> = emptyList(),
    )

    @Serializable
    private data class BookedFillDto(
        val orderId: String,
        val fillId: String,
        val entry: String,
        val positionId: String?,
        val nextPositionId: String?,
        val grossRealized: String,
        val strategyId: String,
        val atMs: Long,
    ) {
        fun toDomain() =
            BookedFill(
                orderId,
                fillId,
                VenueLeg(entry, positionId, nextPositionId),
                BigDecimal(grossRealized),
                strategyId,
                atMs,
            )

        companion object {
            fun of(f: BookedFill) =
                BookedFillDto(
                    f.orderId,
                    f.fillId,
                    f.leg.entry,
                    f.leg.positionId,
                    f.leg.nextPositionId,
                    f.grossRealized.toPlainString(),
                    f.strategyId,
                    f.atMs,
                )
        }
    }
}

private const val BOOKED_DEALS_FILE = "booked-deals.json"
