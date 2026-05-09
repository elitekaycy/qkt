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

@Serializable
data class PositionDto(
    val symbol: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val qty: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val avgPrice: BigDecimal,
)

@Serializable
data class TradeDto(
    val timestamp: String,
    val side: String,
    val symbol: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val qty: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val price: BigDecimal,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val realized: BigDecimal,
)

@Serializable
data class PendingStackLayer(
    val stackId: String,
    val layer: Int,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val triggerPrice: BigDecimal,
    val side: String,
    @Serializable(with = BigDecimalAsNumberSerializer::class) val quantity: BigDecimal,
)

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
)
