package com.qkt.marketdata.ingest.source

import java.nio.file.Path

/**
 * The vendor binary formats qkt reads itself, each turned into rows for its preset: MetaTrader 4 `.hst`
 * ([HstBars]), Databento DBN ([DbnRecords]) and Sierra Chart `.scid` ([ScidRecords]). Dukascopy `.bi5`
 * hours are read by [Bi5Hours], which also counts empty hours.
 */
internal object NativeBinaries {
    /** File extensions a directory walk keeps for these readers. */
    val EXTENSIONS = setOf("hst", "dbn", "zst", "scid")

    /** The input [file] reads as, or null when it is none of these formats; [digits] round float prices. */
    fun input(
        file: Path,
        digits: Int?,
        sha256: () -> String,
    ): ImportInput? =
        when {
            HstBars.isHst(file) -> HstBars.input(file, sha256())
            DbnRecords.isDbn(file) -> DbnRecords.input(file, sha256())
            ScidRecords.isScid(file) -> ScidRecords.input(file, sha256(), digits)
            else -> null
        }
}
