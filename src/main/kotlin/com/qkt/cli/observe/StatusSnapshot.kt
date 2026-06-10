package com.qkt.cli.observe

import java.math.BigDecimal
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Serializes [BigDecimal] as a raw JSON number (not a string) — keeps API consumers happy. */
object BigDecimalAsNumberSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        require(encoder is JsonEncoder) { "BigDecimal serializer requires Json encoder" }
        encoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        require(decoder is JsonDecoder) { "BigDecimal serializer requires Json decoder" }
        return BigDecimal(decoder.decodeJsonElement().toString())
    }
}

/** Open position in a [StatusSnapshot]. */
@Serializable
data class PositionDto(
    val symbol: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val qty: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val avgPrice: BigDecimal,
)

/** Recent trade in a [StatusSnapshot]. */
@Serializable
data class TradeDto(
    val timestamp: String,
    val side: String,
    val symbol: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val qty: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val price: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val realized: BigDecimal,
)

/** Stack layer that hasn't triggered yet — exposed for operator visibility. */
@Serializable
data class PendingStackLayer(
    val stackId: String,
    val layer: Int,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val triggerPrice: BigDecimal,
    val side: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val quantity: BigDecimal,
)

/** JSON payload returned by `/status` — the canonical operator-facing strategy snapshot. */
@Serializable
data class StatusSnapshot(
    val strategy: String,
    val version: Int,
    val uptimeMs: Long,
    val startedAt: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val equity: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val balance: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val realized: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val unrealized: BigDecimal,
    val positions: List<PositionDto>,
    val lastTrade: TradeDto?,
    val pendingStackLayers: List<PendingStackLayer> = emptyList(),
    /**
     * Per-strategy stream → broker label map. Populated for DSL-compiled strategies so
     * `qkt status --deep` can show which broker each declared stream routes to (#139).
     * Empty for plain (non-DSL) strategies or when not yet exposed by the handle.
     */
    val streamBrokers: Map<String, String> = emptyMap(),
    /**
     * Depth of the session's inbound engine queue at snapshot time. A growing value
     * flags a stalled or slow engine thread before backlog turns into dropped ticks.
     */
    val inboundQueueDepth: Int = 0,
    /** Symbols whose market data is currently stale (quote age past threshold). */
    val staleSymbols: List<String> = emptyList(),
)
