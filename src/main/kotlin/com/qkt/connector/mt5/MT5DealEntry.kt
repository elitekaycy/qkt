package com.qkt.connector.mt5

/** MT5 `DEAL_ENTRY_*` codes as names; an unrecognized code passes through as its number. */
internal fun mt5DealEntryName(entry: Int): String =
    when (entry) {
        0 -> "IN"
        1 -> "OUT"
        2 -> "INOUT"
        3 -> "OUT_BY"
        else -> entry.toString()
    }
