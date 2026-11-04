package com.qkt.marketdata.store

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a tick store key lives under a data root. A bare key (`AAPL`) is the shared store
 * `symbols/AAPL/`; a venue-qualified key (`EXNESS:AAPL`) is that venue's own store `ticks/EXNESS/AAPL/`,
 * so two venues quoting the same bare symbol keep apart (a golden capture of `EXNESS:AAPL` beside
 * `ALPACA:AAPL`).
 */
object TickStoreKeys {
    /** The directory holding [key]'s day files and manifest under [root]. */
    fun dir(
        root: Path,
        key: String,
    ): Path {
        val venue = key.substringBefore(':', missingDelimiterValue = "")
        return if (venue.isEmpty()) {
            root.resolve("symbols").resolve(key)
        } else {
            root.resolve("ticks").resolve(venue).resolve(key.substringAfter(':'))
        }
    }

    /**
     * The key a broker-prefixed [symbol] reads its ticks from: its venue's own store when [root] holds
     * one, otherwise the bare-symbol store every data root has always used.
     */
    fun forSymbol(
        root: Path,
        symbol: String,
    ): String = if (':' in symbol && Files.isDirectory(dir(root, symbol))) symbol else symbol.substringAfter(':')
}
