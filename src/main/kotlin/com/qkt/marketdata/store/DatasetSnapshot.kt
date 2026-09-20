package com.qkt.marketdata.store

import kotlinx.serialization.Serializable

/**
 * Content-addressed record of the tick day files one symbol's dataset covered over `[from, to)`,
 * with the quality policy it is verified against; built and checked by [DatasetSnapshots].
 */
@Serializable
data class DatasetSnapshot(
    val schemaVersion: Int = 1,
    val id: String,
    val qktVersion: String,
    val gitSha: String,
    val dataRoot: String,
    val symbol: String,
    val vendor: String,
    val from: String,
    val to: String,
    val qualityPolicy: DataQualityPolicy = DataQualityPolicy(),
    val files: List<DatasetSnapshotFile>,
)

@Serializable
data class DataQualityPolicy(
    val mode: String = "strict",
    val maxGapMinutes: Long = 30,
    val allowEmptyDays: Boolean = false,
    val requireBidAsk: Boolean = false,
    val requireVolume: Boolean = false,
    val failOnCorruptDay: Boolean = true,
    val failOnMissingDay: Boolean = true,
)

@Serializable
data class DatasetSnapshotFile(
    val date: String,
    val path: String,
    val format: String,
    val lineage: String,
    val sizeBytes: Long,
    val sha256: String,
    val tickCount: Int,
    val minTimestamp: Long? = null,
    val maxTimestamp: Long? = null,
    val maxGapMs: Long = 0,
    val readable: Boolean = true,
    val bidAskTicks: Int = 0,
    val volumeTicks: Int = 0,
) {
    val empty: Boolean get() = readable && tickCount == 0
}

data class DatasetVerification(
    val ok: Boolean,
    val failures: List<String>,
)
