package com.qkt.marketdata.store

import java.nio.file.Path
import java.time.LocalDate

interface DataFetcher {
    fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    )
}
