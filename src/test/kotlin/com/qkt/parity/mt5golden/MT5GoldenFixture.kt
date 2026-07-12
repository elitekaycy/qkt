package com.qkt.parity.mt5golden

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class MT5GoldenFixture(
    val schemaVersion: Int,
    val provenance: CaptureProvenance,
    val account: CapturedAccount,
    val instrument: CapturedInstrument,
    val tolerances: ReplayTolerances,
    val ticks: List<CapturedTick>,
    val submissions: List<CapturedSubmission>,
    val venueOrders: List<CapturedVenueOrder>,
    val venueDeals: List<CapturedVenueDeal>,
) {
    fun validate(requireAuthentic: Boolean = true) {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported MT5 golden schema: $schemaVersion" }
        if (requireAuthentic) {
            require(provenance.kind == CaptureKind.MT5_DEMO_SESSION) {
                "MT5 venue evidence requires an authentic demo-session capture"
            }
        }
        require(provenance.capturedAtUtc.isNotBlank()) { "capturedAtUtc is required" }
        require(provenance.captureId.isNotBlank()) { "captureId is required" }
        require(provenance.sourceArtifactSha256.matches(Regex("[0-9a-f]{64}"))) {
            "sourceArtifactSha256 must be a lowercase SHA-256"
        }
        require(account.mode.isNotBlank()) { "account mode is required" }
        require(account.serverTimeZone.isNotBlank()) { "server timezone is required" }
        require(account.brokerProfile.isNotBlank()) { "broker profile is required" }
        require(instrument.qktSymbol.isNotBlank()) { "qktSymbol is required" }
        require(instrument.venueSymbol.isNotBlank()) { "venueSymbol is required" }
        require(decimal(instrument.pointSize).signum() > 0) { "pointSize must be positive" }
        require(decimal(instrument.volumeStep).signum() > 0) { "volumeStep must be positive" }
        require(decimal(instrument.volumeMin).signum() > 0) { "volumeMin must be positive" }
        require(tolerances.pricePoints >= 0) { "pricePoints tolerance must be non-negative" }
        require(tolerances.volumeSteps >= 0) { "volumeSteps tolerance must be non-negative" }
        require(ticks.isNotEmpty()) { "capture must contain ticks" }
        require(submissions.isNotEmpty()) { "capture must contain submitted orders" }
        val timeline = ticks.map { it.sequence } + submissions.map { it.sequence }
        require(timeline.distinct().size == timeline.size) { "capture sequence values must be unique" }
        require(ticks.map { it.sequence }.sorted() == ticks.map { it.sequence }) {
            "ticks must be stored in capture order"
        }
        require(submissions.map { it.sequence }.sorted() == submissions.map { it.sequence }) {
            "submissions must be stored in capture order"
        }
        val eventTimes =
            (
                ticks.map { it.sequence to it.timestampMs } +
                    submissions.map { it.sequence to it.clientOrder.timestampMs }
            ).sortedBy { it.first }
                .map { it.second }
        require(eventTimes.sorted() == eventTimes) { "capture timestamps must not move backwards" }
        ticks.forEach { tick ->
            require(tick.symbol == instrument.qktSymbol) { "tick symbol ${tick.symbol} does not match instrument" }
            require(decimal(tick.bid) <= decimal(tick.ask)) { "tick bid exceeds ask at sequence ${tick.sequence}" }
        }
        val submissionIds = submissions.map { it.clientOrder.id }
        require(submissionIds.distinct().size == submissionIds.size) { "client order ids must be unique" }
        submissions.forEach { submission ->
            require(submission.venueRequest.clientOrderId == submission.clientOrder.id) {
                "translated request id does not match client order ${submission.clientOrder.id}"
            }
            require(submission.clientOrder.symbol == instrument.qktSymbol) {
                "order symbol ${submission.clientOrder.symbol} does not match instrument"
            }
            require(submission.venueRequest.symbol == instrument.venueSymbol) {
                "venue request symbol ${submission.venueRequest.symbol} does not match instrument"
            }
            require(decimal(submission.venueRequest.volume).signum() > 0) { "venue volume must be positive" }
        }
        require(venueOrders.map { it.clientOrderId }.distinct().size == venueOrders.size) {
            "venue order outcomes must be unique by client order id"
        }
        require(venueOrders.map { it.clientOrderId }.toSet() == submissionIds.toSet()) {
            "venue order outcomes must cover every submitted client order exactly once"
        }
        venueDeals.forEach { deal ->
            require(deal.clientOrderId in submissionIds) { "deal ${deal.dealId} has no captured submission" }
            require(deal.dealId.isNotBlank()) { "deal id is required" }
            require(deal.positionTicket.isNotBlank()) { "position ticket is required" }
            require(decimal(deal.executedVolume).signum() > 0) { "deal volume must be positive" }
            require(decimal(deal.price).signum() > 0) { "deal price must be positive" }
            decimal(deal.commission)
            decimal(deal.swap)
            decimal(deal.fee)
        }
        val dealOrderIds = venueDeals.map { it.clientOrderId }.toSet()
        venueOrders.filter { it.status == "FILLED" }.forEach { order ->
            require(order.clientOrderId in dealOrderIds) {
                "filled venue order ${order.clientOrderId} has no captured deal"
            }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        private val json = Json { ignoreUnknownKeys = false }

        fun load(
            path: Path,
            requireAuthentic: Boolean = true,
        ): MT5GoldenFixture =
            json.decodeFromString<MT5GoldenFixture>(Files.readString(path)).also {
                it.validate(requireAuthentic)
            }
    }
}

@Serializable
internal enum class CaptureKind {
    MT5_DEMO_SESSION,
    SYNTHETIC_SCHEMA_TEST,
}

@Serializable
internal data class CaptureProvenance(
    val kind: CaptureKind,
    val capturedAtUtc: String,
    val captureId: String,
    val sourceArtifactSha256: String,
    val sourceHost: String,
    val notes: String,
)

@Serializable
internal data class CapturedAccount(
    val mode: String,
    val serverTimeZone: String,
    val brokerProfile: String,
    val accountCurrency: String,
)

@Serializable
internal data class CapturedInstrument(
    val qktSymbol: String,
    val venueSymbol: String,
    val contractSize: String,
    val volumeStep: String,
    val volumeMin: String,
    val volumeMax: String? = null,
    val pointSize: String,
    val digits: Int,
    val tradeStopsLevelPoints: Int,
)

@Serializable
internal data class ReplayTolerances(
    val pricePoints: Int,
    val volumeSteps: Int,
)

@Serializable
internal data class CapturedTick(
    val sequence: Long,
    val timestampMs: Long,
    val symbol: String,
    val bid: String,
    val ask: String,
)

@Serializable
internal data class CapturedSubmission(
    val sequence: Long,
    val clientOrder: CapturedClientOrder,
    val venueRequest: CapturedVenueRequest,
)

@Serializable
internal enum class CapturedOrderKind {
    MARKET,
    LIMIT,
    STOP,
    STOP_LIMIT,
}

@Serializable
internal data class CapturedClientOrder(
    val id: String,
    val strategyId: String,
    val symbol: String,
    val side: String,
    val kind: CapturedOrderKind,
    val quantity: String,
    val timeInForce: String,
    val timestampMs: Long,
    val expiresAtMs: Long? = null,
    val limitPrice: String? = null,
    val stopPrice: String? = null,
)

@Serializable
internal data class CapturedVenueRequest(
    val clientOrderId: String,
    val symbol: String,
    val action: String,
    val orderType: String,
    val volume: String,
    val price: String? = null,
    val stopLoss: String? = null,
    val takeProfit: String? = null,
    val magic: Long,
    val typeTime: String,
    val expirationMs: Long? = null,
)

@Serializable
internal data class CapturedVenueOrder(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val accepted: Boolean,
    val retcode: Int,
    val status: String,
    val comment: String,
)

@Serializable
internal data class CapturedVenueDeal(
    val clientOrderId: String,
    val dealId: String,
    val positionTicket: String,
    val side: String,
    val executedVolume: String,
    val price: String,
    val retcode: Int,
    val commission: String,
    val swap: String,
    val fee: String,
)

internal fun decimal(value: String): java.math.BigDecimal = value.toBigDecimal()
