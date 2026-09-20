package com.qkt.persistence

/** Schema version written into, and required of, every state file. */
internal const val STATE_SCHEMA_VERSION = 1

/** Name of a per-symbol state file: `<symbol>-<base>`, e.g. `XAUUSDm-legbook.json`. */
internal fun symbolStateFileName(
    symbol: String,
    base: String,
): String = "$symbol-$base"
