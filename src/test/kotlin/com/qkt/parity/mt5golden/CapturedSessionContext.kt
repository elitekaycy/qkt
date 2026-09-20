package com.qkt.parity.mt5golden

import kotlinx.serialization.Serializable

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
