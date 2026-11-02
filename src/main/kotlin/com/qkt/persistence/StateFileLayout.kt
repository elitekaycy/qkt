package com.qkt.persistence

import com.qkt.common.FileNameSegment

/** Schema version written into, and required of, every state file. */
internal const val STATE_SCHEMA_VERSION = 1

/**
 * Name of a per-symbol state file: `<symbol>-<base>`, e.g. `XAUUSDm-legbook.json`. On Windows a
 * symbol such as `CME:ES` is encoded by [FileNameSegment] (`CME%3AES-lane.json`).
 */
internal fun symbolStateFileName(
    symbol: String,
    base: String,
): String = "${FileNameSegment.encode(symbol)}-$base"

/** The symbol of a per-symbol state file named [fileName], or null when it is not a `-[base]` file. */
internal fun symbolOfStateFileName(
    fileName: String,
    base: String,
): String? = fileName.takeIf { it.endsWith("-$base") }?.removeSuffix("-$base")?.let { FileNameSegment.decode(it) }
