package com.qkt.marketdata.store

import kotlinx.serialization.Serializable

/**
 * Per-(broker, symbol, timeframe) manifest tracking which UTC days the local
 * bar store has on disk. `qkt fetch` updates this after every successful write
 * so subsequent fetches and backtest reads can short-circuit on the date ranges
 * already covered.
 */
@Serializable
data class BarsManifest(
    val schemaVersion: Int = 1,
    val schema: String = "qkt-bars-csv-v1",
    val broker: String,
    val symbol: String,
    val timeframe: String,
    val ranges: List<DayRange> = emptyList(),
    val lastUpdated: String = "",
)
