package com.qkt.marketdata.store

import kotlinx.serialization.Serializable

@Serializable
data class Manifest(
    val schemaVersion: Int = 1,
    val schema: String = "qkt-csv-v1",
    val symbol: String,
    val ranges: List<DayRange> = emptyList(),
    val lastUpdated: String = "",
)

@Serializable
data class DayRange(
    val from: String,
    val to: String,
)
