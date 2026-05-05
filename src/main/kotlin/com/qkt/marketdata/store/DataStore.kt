package com.qkt.marketdata.store

import com.qkt.marketdata.TickFeed
import java.nio.file.Path
import java.time.LocalDate

interface DataStore {
    val root: Path

    fun manifest(symbol: String): Manifest

    fun dayFile(
        symbol: String,
        day: LocalDate,
    ): Path?

    fun openFeed(request: DataRequest): TickFeed

    fun prefetch(request: DataRequest)

    fun rebuildManifests()
}
