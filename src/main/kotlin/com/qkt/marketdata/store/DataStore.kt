package com.qkt.marketdata.store

import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketRequest
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

interface DataStore {
    val root: Path

    fun manifest(symbol: String): Manifest

    fun dayFile(
        symbol: String,
        day: LocalDate,
    ): Path?

    fun openFeed(request: MarketRequest): TickFeed

    fun resolveRange(request: MarketRequest): Pair<Instant, Instant>

    fun prefetch(request: MarketRequest)

    fun rebuildManifests()
}
