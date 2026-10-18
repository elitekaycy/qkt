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
    /**
     * Days of the range with no file: a closed market's weekend, which an import does not write (a fetch writes
     * an empty file). Pinned too, so a file appearing there later is a change.
     */
    val absentDays: List<String> = emptyList(),
)

@Serializable
data class DataQualityPolicy(
    val mode: String = "strict",
    val maxGapMinutes: Long = DEFAULT_MAX_GAP_MINUTES,
    val allowEmptyDays: Boolean = false,
    val requireBidAsk: Boolean = false,
    val requireVolume: Boolean = false,
    val failOnCorruptDay: Boolean = true,
    val failOnMissingDay: Boolean = true,
) {
    companion object {
        /**
         * The largest gap between two ticks of one day a dataset accepts by default: an hour-long daily break
         * (gold, futures: 21:00-22:00 UTC, 62-66 minutes in Exness captures) passes, a hole of hours does not.
         */
        const val DEFAULT_MAX_GAP_MINUTES = 90L
    }
}

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
